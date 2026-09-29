package com.sf.streamingesp

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * ESP32-CAM 영상 위에 전신 리깅을 그리는 화면. 이 앱에는 이 화면 하나뿐이고 메뉴도 버튼도 없다.
 * 실행하면 바로 카메라에 붙고, 사람이 잡히면 무조건 스켈레톤이 오버레이된다.
 *
 * 스레드 구성:
 *   MJPEG 스레드   — 연결/파싱/JPEG 디코드. 최신 프레임 한 장만 들고 있는다.
 *   추론 스레드     — 최신 프레임을 가져와 YOLOv8-pose 를 돌리고, 프레임과 결과를 함께 뷰로 넘긴다.
 *   UI 스레드      — 넘겨받은 (프레임, 리그) 쌍을 그린다.
 *
 * 프레임과 결과를 쌍으로 넘기는 이유: 최신 영상을 따로 그리고 결과만 나중에 얹으면 추론에
 * 걸린 시간만큼 스켈레톤이 몸에서 밀린다. 표시 프레임레이트를 추론 속도에 맞추는 대신
 * 관절이 항상 몸에 붙어 있게 한다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var view: RigOverlayView

    private var engine: PoseEngine? = null
    private var stream: MjpegStream? = null

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val frameLock = java.lang.Object()
    private var pendingFrame: Bitmap? = null

    @Volatile private var running = false
    private var worker: Thread? = null

    // 검출이 한두 프레임 비었다고 스켈레톤을 지우면 심하게 깜빡인다. 이 시간 동안은 직전 결과를 유지한다.
    private val holdMs = 450L
    // 관절 좌표 지수평활. 1에 가까울수록 반응이 빠르고 떨림이 심해진다.
    private val smoothAlpha = 0.55f
    // 사람이라고 인정하기 위한 최소 관절 수. 낮추면 사물에도 스켈레톤이 붙는다.
    private val minVisibleJoints = 5
    private val minKeypointScore = 0.30f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        view = RigOverlayView(this)
        setContentView(view)

        // 태블릿을 세워두고 보는 화면이다. 절전으로 꺼지면 안 된다.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        view.showStatus("리깅 엔진 준비 중…")

        bindToCameraNetwork()

        // 모델 로드는 13MB 파싱이라 UI 스레드에서 하면 화면이 잠깐 멈춘다.
        Thread({
            engine = try {
                PoseEngine(this)
            } catch (e: Throwable) {
                Log.e(TAG, "모델 로드 실패", e)
                runOnUiThread { view.showStatus("리깅 엔진을 불러오지 못했습니다\n${e.message}") }
                return@Thread
            }
            startPipeline()
        }, "engine-init").start()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, view).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * 카메라 AP 로 프로세스 트래픽을 고정한다.
     *
     * ESP32-CAM 의 SoftAP 에는 인터넷이 없다. 그대로 두면 안드로이드가 이 Wi-Fi 를 "인터넷 없음"
     * 으로 판단해 기본 경로를 모바일 데이터로 되돌리고, 192.168.4.1 요청이 셀룰러로 나가 실패한다.
     * INTERNET 능력을 뺀 Wi-Fi 네트워크를 직접 요청해 그 네트워크에 프로세스를 묶는다.
     */
    private fun bindToCameraNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                Log.i(TAG, "Wi-Fi 네트워크에 바인딩")
            }

            override fun onLost(network: Network) {
                cm.bindProcessToNetwork(null)
            }
        }
        netCallback = cb
        runCatching { cm.requestNetwork(request, cb) }
            .onFailure { Log.w(TAG, "네트워크 바인딩 실패: ${it.message}") }
    }

    private fun startPipeline() {
        running = true

        stream = MjpegStream(
            url = StreamConfig.STREAM_URL,
            onFrame = { bmp ->
                synchronized(frameLock) {
                    // 추론이 따라오지 못해 남아 있던 프레임은 버린다. 큐를 쌓으면 지연만 늘어난다.
                    pendingFrame?.recycle()
                    pendingFrame = bmp
                    frameLock.notifyAll()
                }
            },
            onState = { text -> runOnUiThread { view.showStatus(text) } }
        ).also { it.start() }

        worker = Thread({ inferenceLoop() }, "pose").apply { isDaemon = true; start() }
    }

    private fun inferenceLoop() {
        val eng = engine ?: return
        val result = PoseEngine.Result()
        val raw = Rig.Pose()
        val smoothed = Rig.Pose()
        var hasSmoothed = false
        var lastSeen = 0L

        while (running) {
            var bmp: Bitmap? = null
            synchronized(frameLock) {
                while (running && pendingFrame == null) {
                    try { frameLock.wait(200) } catch (_: InterruptedException) { return }
                }
                bmp = pendingFrame
                pendingFrame = null
            }
            val frame = bmp ?: continue
            if (!running) break

            var pose: Rig.Pose? = null
            val found = try {
                eng.detect(frame, result)
            } catch (e: Throwable) {
                Log.w(TAG, "추론 실패: ${e.message}")
                false
            }

            if (found) {
                Rig.build(result.kx, result.ky, result.ks, minKeypointScore, raw)
                if (raw.visibleCount >= minVisibleJoints) {
                    applySmoothing(raw, smoothed, hasSmoothed)
                    hasSmoothed = true
                    lastSeen = SystemClock.elapsedRealtime()
                    pose = smoothed
                }
            }

            if (pose == null) {
                // 잠깐 놓친 것이면 직전 스켈레톤을 유지하고, 오래 안 보이면 지운다.
                pose = if (hasSmoothed && SystemClock.elapsedRealtime() - lastSeen < holdMs) {
                    smoothed
                } else {
                    hasSmoothed = false
                    null
                }
            }

            val posed = pose
            runOnUiThread { view.submit(frame, posed) }
        }
    }

    private fun applySmoothing(src: Rig.Pose, dst: Rig.Pose, hasPrev: Boolean) {
        for (i in 0 until Rig.JOINT_COUNT) {
            if (!src.valid[i]) {
                dst.valid[i] = false
                continue
            }
            if (!hasPrev || !dst.valid[i]) {
                dst.x[i] = src.x[i]
                dst.y[i] = src.y[i]
            } else {
                dst.x[i] += (src.x[i] - dst.x[i]) * smoothAlpha
                dst.y[i] += (src.y[i] - dst.y[i]) * smoothAlpha
            }
            dst.valid[i] = true
        }
    }

    override fun onDestroy() {
        running = false
        synchronized(frameLock) { frameLock.notifyAll() }
        stream?.stop()
        worker?.interrupt()
        engine?.close()

        netCallback?.let { cb ->
            getSystemService(ConnectivityManager::class.java)?.let { cm ->
                runCatching { cm.unregisterNetworkCallback(cb) }
                runCatching { cm.bindProcessToNetwork(null) }
            }
        }
        super.onDestroy()
    }

    private companion object { const val TAG = "Rig" }
}
