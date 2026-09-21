package com.kaan.jarvismobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class AuroraCoreView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var state = "OFFLINE"

    fun setState(value: String) { state = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * .29f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(165, 80, 227, 255)
        canvas.drawCircle(cx, cy, r, paint)
        paint.strokeWidth = 1f
        paint.color = Color.argb(90, 91, 255, 207)
        canvas.drawCircle(cx, cy, r * .72f, paint)
        val count = 72
        for (i in 0 until count) {
            val a = i * (Math.PI * 2 / count).toFloat() + phase
            val wobble = 1f + .09f * sin(phase * 2f + i * .71f)
            val rr = r * wobble
            val x = cx + cos(a) * rr
            val y = cy + sin(a) * rr
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(70 + (i % 5) * 20, 92, 232, 255)
            canvas.drawCircle(x, y, 1.8f + (i % 3) * .45f, paint)
        }
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(219, 251, 255)
        paint.textSize = r * .22f
        paint.isFakeBoldText = true
        canvas.drawText("JARVIS", cx, cy + 4f, paint)
        paint.isFakeBoldText = false
        paint.textSize = r * .09f
        paint.color = Color.rgb(104, 180, 202)
        canvas.drawText(state, cx, cy + r * .28f, paint)
        phase += .008f
        postInvalidateOnAnimation()
    }
}
