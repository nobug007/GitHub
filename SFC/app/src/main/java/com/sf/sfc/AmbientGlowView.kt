package com.sf.sfc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/**
 * 시안의 시그니처 요소인 "앰비언트 글로우".
 *
 * 화면 상단에 은은한 원형 빛을 깔아, 보호자가 글자를 읽기 전에 상태를 색으로 먼저 알아채도록 한다.
 * 평온할 때는 틸, 주의가 필요하면 앰버, 긴급이면 벽돌빛이다.
 *
 * BlurMaskFilter 대신 알파가 0으로 떨어지는 RadialGradient 를 쓴다. 결과는 같으면서 하드웨어 가속을
 * 끌 필요가 없다.
 */
@SuppressLint("ViewConstructor")
class AmbientGlowView(context: Context, glowColor: Int) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var glowColor: Int = glowColor
        set(value) {
            if (field == value) return
            field = value
            rebuildShader(width, height)
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShader(w, h)
    }

    private fun rebuildShader(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        // 시안과 같은 위치: 왼쪽으로 치우치고 화면 위쪽 밖에서 시작해 아래로 번진다.
        //
        // 반지름은 뷰 높이에 맞춰 잡아야 한다. 반지름이 뷰보다 훨씬 크면 그라데이션이 다 끝나기 전에
        // 뷰가 잘려서, 아래쪽에 색이 남은 채로 직선 경계가 생긴다(단색 띠처럼 보인다).
        val centerX = w * 0.30f
        val centerY = -h * 0.15f
        val radius = h * 1.25f
        paint.shader = RadialGradient(
            centerX, centerY, radius,
            intArrayOf(
                alpha(glowColor, 0.55f),
                alpha(glowColor, 0.22f),
                alpha(glowColor, 0f)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (paint.shader == null) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun alpha(color: Int, fraction: Float): Int =
        Color.argb((255 * fraction).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
