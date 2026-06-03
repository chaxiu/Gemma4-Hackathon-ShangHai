package com.winter.durianai.floating

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.roundToInt

class FloatingTrashTargetView(context: Context) : View(context) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val binBody = RectF()
    private var active = false

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        animate()
            .scaleX(if (active) 1.08f else 1f)
            .scaleY(if (active) 1.08f else 1f)
            .setDuration(120L)
            .start()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = defaultSizePx(context)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = width / 2f
        backgroundPaint.color = if (active) 0xFFE86A5E.toInt() else 0xCC24302D.toInt()
        iconPaint.color = 0xFFFFFFFF.toInt()
        iconFillPaint.color = 0x22FFFFFF
        iconPaint.strokeWidth = width * 0.055f

        canvas.drawCircle(radius, radius, radius * 0.92f, backgroundPaint)

        val bodyLeft = width * 0.32f
        val bodyTop = height * 0.4f
        val bodyRight = width * 0.68f
        val bodyBottom = height * 0.72f
        binBody.set(bodyLeft, bodyTop, bodyRight, bodyBottom)
        canvas.drawRoundRect(binBody, width * 0.045f, width * 0.045f, iconFillPaint)
        canvas.drawRoundRect(binBody, width * 0.045f, width * 0.045f, iconPaint)

        canvas.drawLine(width * 0.28f, height * 0.34f, width * 0.72f, height * 0.34f, iconPaint)
        canvas.drawLine(width * 0.43f, height * 0.27f, width * 0.57f, height * 0.27f, iconPaint)
        canvas.drawLine(width * 0.47f, height * 0.46f, width * 0.47f, height * 0.65f, iconPaint)
        canvas.drawLine(width * 0.53f, height * 0.46f, width * 0.53f, height * 0.65f, iconPaint)
    }

    companion object {
        fun defaultSizePx(context: Context): Int {
            return (78 * context.resources.displayMetrics.density).roundToInt()
        }

        fun bottomMarginPx(context: Context): Int {
            return (34 * context.resources.displayMetrics.density).roundToInt()
        }
    }
}
