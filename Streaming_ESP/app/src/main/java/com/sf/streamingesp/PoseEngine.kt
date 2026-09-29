package com.sf.streamingesp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLOv8n-pose(ONNX)를 ONNX Runtime 으로 돌려 사람 한 명의 COCO 17 키포인트를 뽑는다.
 *
 * 모델 규격 (Ultralytics YOLOv8n-pose, kpt_shape [17,3]):
 *   입력  images  [1, 3, 640, 640]  RGB, 0~1 정규화, letterbox 로 비율 유지
 *   출력  output0 [1, 56, 8400]     56 = 4(박스 cx,cy,w,h) + 1(person 신뢰도) + 17*3(x,y,score)
 *
 * 출력은 채널 우선으로 눕혀져 있어 인덱스가 `channel * 8400 + candidate` 가 된다.
 * 후보 8400개 중 최고 신뢰도 하나만 쓰므로 NMS 는 필요 없다 — 화면 안의 한 사람만 그린다.
 */
class PoseEngine(context: Context) {

    /** 한 프레임의 결과. 좌표는 원본 프레임(레터박스 되돌린) 픽셀 단위다. */
    class Result {
        val kx = FloatArray(17)
        val ky = FloatArray(17)
        val ks = FloatArray(17)
        var personScore = 0f
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    private val inputSize = 640
    private val inputData = FloatArray(3 * 640 * 640)
    private val inputBuffer: FloatBuffer = FloatBuffer.wrap(inputData)

    // letterbox 캔버스와 픽셀 버퍼는 프레임마다 재사용한다. 매 프레임 640x640 비트맵을 새로
    // 만들면 GC 가 추론보다 더 오래 걸린다.
    private val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    private val letterboxCanvas = Canvas(letterboxed)
    private val pixels = IntArray(inputSize * inputSize)
    private val dstRect = Rect()
    private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var scale = 1f
    private var padX = 0
    private var padY = 0

    init {
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val opts = OrtSession.SessionOptions()
        // XNNPACK 이 붙으면 CPU 추론이 눈에 띄게 빨라진다. 이때 ORT 자체 스레드풀은 1로 두는 것이
        // 권장 조합이다 (XNNPACK 이 내부에서 병렬화한다). 없는 기기에서는 조용히 기본 CPU 로 간다.
        val xnnpack = runCatching {
            opts.addXnnpack(mapOf("intra_op_num_threads" to THREADS.toString()))
        }.isSuccess
        opts.setIntraOpNumThreads(if (xnnpack) 1 else THREADS)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

        session = env.createSession(model, opts)
        inputName = session.inputNames.first()
        Log.i(TAG, "모델 로드 완료 (XNNPACK=$xnnpack, input=$inputName)")
    }

    /**
     * [frame] 에서 사람을 찾아 [out] 에 키포인트를 채운다.
     * @return 사람을 찾았으면 true. 신뢰도가 [minPersonScore] 미만이면 false.
     */
    fun detect(frame: Bitmap, out: Result, minPersonScore: Float = 0.45f): Boolean {
        preprocess(frame)

        OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, 3, 640, 640)).use { input ->
            session.run(mapOf(inputName to input)).use { results ->
                val tensor = results[0] as OnnxTensor
                val shape = (tensor.info as TensorInfo).shape          // [1, 56, N]
                val channels = shape[1].toInt()
                val candidates = shape[2].toInt()
                val data = tensor.floatBuffer

                // person 신뢰도는 5번째 채널(index 4).
                var best = -1
                var bestScore = minPersonScore
                val confBase = 4 * candidates
                for (i in 0 until candidates) {
                    val s = data.get(confBase + i)
                    if (s > bestScore) { bestScore = s; best = i }
                }
                if (best < 0) return false

                out.personScore = bestScore
                for (j in 0 until 17) {
                    val cx = 5 + j * 3
                    if (cx + 2 >= channels) break
                    val x = data.get(cx * candidates + best)
                    val y = data.get((cx + 1) * candidates + best)
                    out.ks[j] = data.get((cx + 2) * candidates + best)
                    // letterbox 되돌리기: 패딩을 빼고 스케일로 나누면 원본 프레임 좌표가 된다.
                    out.kx[j] = (x - padX) / scale
                    out.ky[j] = (y - padY) / scale
                }
                return true
            }
        }
    }

    /** 비율을 유지한 채 640x640 한가운데에 얹고, RGB 평면 순서(NCHW)로 펼친다. */
    private fun preprocess(frame: Bitmap) {
        val w = frame.width
        val h = frame.height
        scale = min(inputSize / w.toFloat(), inputSize / h.toFloat())
        val newW = (w * scale).roundToInt()
        val newH = (h * scale).roundToInt()
        padX = (inputSize - newW) / 2
        padY = (inputSize - newH) / 2

        // 회색 114 는 Ultralytics 가 학습·추론에 쓰는 패딩 색이다. 검정으로 채우면 가장자리에서
        // 모델이 실제와 다른 대비를 보게 된다.
        letterboxCanvas.drawColor(Color.rgb(114, 114, 114))
        dstRect.set(padX, padY, padX + newW, padY + newH)
        letterboxCanvas.drawBitmap(frame, null, dstRect, blitPaint)

        letterboxed.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val plane = inputSize * inputSize
        for (i in 0 until plane) {
            val p = pixels[i]
            inputData[i] = ((p shr 16) and 0xFF) / 255f              // R
            inputData[plane + i] = ((p shr 8) and 0xFF) / 255f       // G
            inputData[2 * plane + i] = (p and 0xFF) / 255f           // B
        }
        inputBuffer.rewind()
    }

    fun close() {
        runCatching { session.close() }
    }

    private companion object {
        const val TAG = "PoseEngine"
        const val MODEL_ASSET = "yolov8n-pose.onnx"
        val THREADS = min(4, Runtime.getRuntime().availableProcessors())
    }
}
