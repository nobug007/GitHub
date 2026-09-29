package com.sf.streamingesp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 카메라 프레임과 리깅을 같은 캔버스에 그린다.
 *
 * 영상과 스켈레톤을 별도 뷰로 겹치면 두 뷰의 스케일링이 미세하게 달라 관절이 몸에서 밀린다.
 * 한 캔버스에서 같은 레터박스 변환을 쓰면 그런 어긋남이 생길 수 없다.
 */
class RigOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var frame: Bitmap? = null
    private var pose: Rig.Pose? = null
    private var status: String? = "시작하는 중…"

    private val dstRect = Rect()

    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE600")      // 뼈대: 노랑
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // 밝은 배경에서 노란 선이 묻히지 않도록 먼저 깔아주는 어두운 외곽선.
    private val boneEdgePaint = Paint(bonePaint).apply { color = Color.parseColor("#8C000000") }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF66")      // 관절: 초록
        style = Paint.Style.FILL
    }
    private val dotEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8C000000")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CFD8DC")
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }

    /** 추론에 실제로 쓰인 프레임과 그 결과를 함께 넘긴다 — 그래야 오버레이가 몸에서 밀리지 않는다. */
    fun submit(bitmap: Bitmap, rig: Rig.Pose?) {
        frame = bitmap
        pose = rig
        status = null
        postInvalidateOnAnimation()
    }

    fun showStatus(text: String?) {
        status = text
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        val bmp = frame
        if (bmp != null && !bmp.isRecycled) {
            val scale = min(width / bmp.width.toFloat(), height / bmp.height.toFloat())
            val drawW = (bmp.width * scale).roundToInt()
            val drawH = (bmp.height * scale).roundToInt()
            val dx = (width - drawW) / 2
            val dy = (height - drawH) / 2
            dstRect.set(dx, dy, dx + drawW, dy + drawH)
            canvas.drawBitmap(bmp, null, dstRect, framePaint)

            pose?.let { drawRig(canvas, it, scale, dx.toFloat(), dy.toFloat()) }
        }

        status?.let { drawStatus(canvas, it) }
    }

    private fun drawRig(canvas: Canvas, p: Rig.Pose, scale: Float, dx: Float, dy: Float) {
        // 선 굵기와 점 크기를 화면상 사람 크기에 비례시킨다. 멀리 있는 사람에게 굵은 선을 그으면
        // 스켈레톤이 몸을 통째로 덮어버린다.
        val span = spanOf(p, scale)
        val boneW = max(2.5f, min(12f, span * 0.022f))
        val dotR = max(3.5f, min(15f, span * 0.030f))

        bonePaint.strokeWidth = boneW
        boneEdgePaint.strokeWidth = boneW + 3.5f

        // 외곽선 → 노란 선 순서로 두 번 훑는다.
        for (paint in arrayOf(boneEdgePaint, bonePaint)) {
            for (bone in Rig.BONES) {
                val a = bone[0]
                val b = bone[1]
                if (!p.valid[a] || !p.valid[b]) continue
                canvas.drawLine(
                    dx + p.x[a] * scale, dy + p.y[a] * scale,
                    dx + p.x[b] * scale, dy + p.y[b] * scale,
                    paint
                )
            }
        }

        for (i in 0 until Rig.JOINT_COUNT) {
            if (!p.valid[i]) continue
            val cx = dx + p.x[i] * scale
            val cy = dy + p.y[i] * scale
            canvas.drawCircle(cx, cy, dotR, dotPaint)
            canvas.drawCircle(cx, cy, dotR, dotEdgePaint)
        }
    }

    /** 리그 바운딩 박스의 대각선. 화면에서 사람이 차지하는 크기를 대충 나타낸다. */
    private fun spanOf(p: Rig.Pose, scale: Float): Float {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until Rig.JOINT_COUNT) {
            if (!p.valid[i]) continue
            if (p.x[i] < minX) minX = p.x[i]
            if (p.y[i] < minY) minY = p.y[i]
            if (p.x[i] > maxX) maxX = p.x[i]
            if (p.y[i] > maxY) maxY = p.y[i]
        }
        if (minX == Float.MAX_VALUE) return 200f
        return hypot(maxX - minX, maxY - minY) * scale
    }

    private fun drawStatus(canvas: Canvas, text: String) {
        val lines = text.split('\n')
        val lineHeight = statusPaint.textSize * 1.5f
        var y = height / 2f - (lines.size - 1) * lineHeight / 2f
        for (line in lines) {
            canvas.drawText(line, width / 2f, y, statusPaint)
            y += lineHeight
        }
    }
}
