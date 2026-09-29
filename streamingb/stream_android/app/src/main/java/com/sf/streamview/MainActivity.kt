package com.sf.streamview

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * ESP32-CAM 이 내보내는 RTSP 영상을 그대로 보여주기만 하는 화면. 버튼도 메뉴도 없다.
 *
 * 재생은 libVLC 가 맡는다. ESP32 는 MJPEG 를 RTP 로 실어 보내는데, ExoPlayer/Media3 의 RTSP
 * 구현은 MJPEG 페이로드를 지원하지 않아 이 조합에서는 쓸 수 없다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var statusView: TextView

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    // UDP 로 먼저 붙어보고, 실패하면 TCP(interleaved)로 바꿔 다시 시도한다. 두 방식 중 어느
    // 쪽이 되는지는 서버 구현과 망 상태에 달려 있어서, 하나만 고정하면 붙을 수 있는데도
    // 못 붙는 경우가 생긴다.
    private var useTcp = false
    private var attempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        videoLayout = findViewById(R.id.video)
        statusView = findViewById(R.id.status)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        bindToCameraNetwork()

        libVlc = LibVLC(this, ArrayList<String>().apply {
            add("--no-audio")            // 이 스트림에는 오디오가 없다
            add("--no-sub-autodetect-file")
            add("--avcodec-skiploopfilter=0")
            add("--clock-jitter=0")      // 지연을 늘려 매끄럽게 만드는 보정을 끈다
            add("--clock-synchro=0")
        })
        player = MediaPlayer(libVlc).apply {
            attachViews(videoLayout, null, false, false)
            setEventListener { event -> onPlayerEvent(event) }
        }

        startPlayback()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, videoLayout).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * 카메라 AP 로 프로세스 트래픽을 고정한다.
     *
     * ESP32 의 SoftAP 에는 인터넷이 없다. 그대로 두면 안드로이드가 이 Wi-Fi 를 "인터넷 없음"
     * 으로 판단해 기본 경로를 다른 망으로 돌리고, 192.168.4.1 로 가는 패킷이 엉뚱한 곳으로
     * 나가 실패한다. INTERNET 능력을 뺀 Wi-Fi 를 직접 요청해 그 네트워크에 프로세스를 묶는다.
     * 이 바인딩은 libVLC 의 네이티브 소켓에도 적용된다.
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

    private fun startPlayback() {
        val vlc = libVlc ?: return
        val mp = player ?: return
        attempts++

        val media = Media(vlc, Uri.parse(STREAM_URL)).apply {
            setHWDecoderEnabled(true, false)
            // 버퍼를 짧게 잡아야 화면이 실시간에 가깝다. 늘리면 부드러워지지만 그만큼 밀린다.
            addOption(":network-caching=200")
            addOption(":live-caching=200")
            if (useTcp) addOption(":rtsp-tcp")
        }
        mp.media = media
        media.release()

        Log.i(TAG, "재생 시도 #$attempts (${if (useTcp) "TCP" else "UDP"}) $STREAM_URL")
        setStatus("카메라 연결 중…\n${if (useTcp) "TCP" else "UDP"} · 시도 $attempts")
        mp.play()
    }

    private fun onPlayerEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Vout -> {
                // 첫 영상 프레임이 실제로 그려진 시점. 여기서만 안내를 지운다.
                if (event.voutCount > 0) {
                    Log.i(TAG, "영상 출력 시작")
                    setStatus(null)
                }
            }
            MediaPlayer.Event.EncounteredError -> {
                Log.w(TAG, "재생 오류 — 방식을 바꿔 재시도")
                retryLater()
            }
            MediaPlayer.Event.EndReached -> {
                Log.w(TAG, "스트림 종료 — 재접속")
                retryLater()
            }
        }
    }

    private fun retryLater() {
        // 매 실패마다 UDP <-> TCP 를 번갈아 시도한다.
        useTcp = !useTcp
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            runCatching { player?.stop() }
            startPlayback()
        }, RETRY_DELAY_MS)
    }

    private fun setStatus(text: String?) {
        if (text == null) {
            statusView.visibility = View.GONE
        } else {
            statusView.text = text
            statusView.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.let {
            runCatching { it.stop() }
            runCatching { it.detachViews() }
            it.release()
        }
        libVlc?.release()
        player = null
        libVlc = null

        netCallback?.let { cb ->
            getSystemService(ConnectivityManager::class.java)?.let { cm ->
                runCatching { cm.unregisterNetworkCallback(cb) }
                runCatching { cm.bindProcessToNetwork(null) }
            }
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "StreamView"
        // stream_esp32 의 SoftAP 게이트웨이와 RTSP 포트.
        const val STREAM_URL = "rtsp://192.168.4.1:554/"
        const val RETRY_DELAY_MS = 1500L
    }
}
