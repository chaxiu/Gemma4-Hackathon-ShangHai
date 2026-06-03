package com.winter.durianai.floating

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import com.winter.durianai.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class FloatingBallView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val onClick: () -> Unit,
    private val onDragStart: () -> Unit,
    private val onDragMove: (centerX: Float, centerY: Float) -> Boolean,
    private val onDragEnd: (centerX: Float, centerY: Float) -> Boolean,
    private val onClose: () -> Unit
) : View(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val ballSize = defaultSizePx(context)
    private val edgeMargin = edgeMarginPx(context)
    private val logoBitmap: Bitmap? = BitmapFactory.decodeResource(resources, R.drawable.doran_logo)
    private val logoPaint = android.graphics.Paint(
        android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG
    )
    private val logoBounds = RectF()
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var wasDockedOnTouchDown = false
    private var isDragging = false
    private var dockAnimator: ValueAnimator? = null

    private val autoDockRunnable = Runnable { dockToNearestEdge() }

    init {
        alpha = 1f
        elevation = dp(12).toFloat()
        scheduleAutoDock()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(ballSize, ballSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        logoBitmap?.let { bitmap ->
            val logoInset = width * 0.04f
            logoBounds.set(logoInset, logoInset, width - logoInset, height - logoInset)
            canvas.drawBitmap(bitmap, null, logoBounds, logoPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                cancelAutoDock()
                wasDockedOnTouchDown = isDocked()
                restoreFromDock()
                downRawX = event.rawX
                downRawY = event.rawY
                startX = layoutParams.x
                startY = layoutParams.y
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging && hypot(dx, dy) > touchSlop) {
                    isDragging = true
                    onDragStart()
                }
                layoutParams.x = startX + dx.roundToInt()
                layoutParams.y = (startY + dy.roundToInt()).coerceIn(0, maxY())
                updateLayout()
                if (isDragging) {
                    onDragMove(layoutParams.x + ballSize / 2f, layoutParams.y + ballSize / 2f)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val distance = hypot(event.rawX - downRawX, event.rawY - downRawY)
                if (isDragging) {
                    val shouldClose = event.actionMasked == MotionEvent.ACTION_UP &&
                        onDragEnd(layoutParams.x + ballSize / 2f, layoutParams.y + ballSize / 2f)
                    isDragging = false
                    if (shouldClose) {
                        onClose()
                    } else {
                        scheduleAutoDock()
                    }
                } else if (event.actionMasked == MotionEvent.ACTION_UP && distance < touchSlop) {
                    if (wasDockedOnTouchDown) {
                        scheduleAutoDock()
                    } else {
                        onClick()
                    }
                } else {
                    isDragging = false
                    scheduleAutoDock()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        cancelAutoDock()
        super.onDetachedFromWindow()
    }

    private fun restoreFromDock() {
        dockAnimator?.cancel()
        animate().alpha(1f).setDuration(120L).start()
        animate().rotation(0f).setDuration(120L).start()

        if (!isDocked()) return

        val screenWidth = windowManager.currentWindowMetrics.bounds.width()
        val rightHiddenX = screenWidth - (ballSize * DockVisibleRatio).roundToInt()
        when {
            layoutParams.x < 0 -> {
                layoutParams.x = edgeMargin
                updateLayout()
            }
            abs(layoutParams.x - rightHiddenX) < edgeMargin * 2 -> {
                layoutParams.x = screenWidth - ballSize - edgeMargin
                updateLayout()
            }
        }
    }

    private fun isDocked(): Boolean {
        val screenWidth = windowManager.currentWindowMetrics.bounds.width()
        val leftHiddenX = -(ballSize * (1f - DockVisibleRatio)).roundToInt()
        val rightHiddenX = screenWidth - (ballSize * DockVisibleRatio).roundToInt()
        return abs(layoutParams.x - leftHiddenX) < edgeMargin * 2 ||
            abs(layoutParams.x - rightHiddenX) < edgeMargin * 2
    }

    private fun scheduleAutoDock() {
        handler.removeCallbacks(autoDockRunnable)
        handler.postDelayed(autoDockRunnable, AutoDockDelayMs)
    }

    private fun cancelAutoDock() {
        handler.removeCallbacks(autoDockRunnable)
        dockAnimator?.cancel()
    }

    private fun dockToNearestEdge() {
        val screenWidth = windowManager.currentWindowMetrics.bounds.width()
        val dockToLeft = layoutParams.x + ballSize / 2 < screenWidth / 2
        val targetX = if (layoutParams.x + ballSize / 2 < screenWidth / 2) {
            -(ballSize * (1f - DockVisibleRatio)).roundToInt()
        } else {
            screenWidth - (ballSize * DockVisibleRatio).roundToInt()
        }

        dockAnimator = ValueAnimator.ofInt(layoutParams.x, targetX).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                layoutParams.x = animator.animatedValue as Int
                layoutParams.y = layoutParams.y.coerceIn(0, maxY())
                updateLayout()
            }
            doOnEnd {
                val targetRotation = if (dockToLeft) DockedLeftRotation else DockedRightRotation
                animate()
                    .alpha(DockedAlpha)
                    .rotation(targetRotation)
                    .setDuration(180L)
                    .start()
            }
            start()
        }
    }

    private fun updateLayout() {
        runCatching { windowManager.updateViewLayout(this, layoutParams) }
    }

    private fun maxY(): Int {
        return (windowManager.currentWindowMetrics.bounds.height() - ballSize - edgeMargin).coerceAtLeast(0)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    companion object {
        private const val AutoDockDelayMs = 3_000L
        private const val DockedAlpha = 0.78f
        private const val DockVisibleRatio = 0.6f
        private const val DockedLeftRotation = 12f
        private const val DockedRightRotation = -12f

        fun defaultSizePx(context: Context): Int {
            return (56 * context.resources.displayMetrics.density).roundToInt() + 5
        }

        fun edgeMarginPx(context: Context): Int {
            return (12 * context.resources.displayMetrics.density).roundToInt()
        }

        fun initialTopOffsetPx(context: Context): Int {
            return (112 * context.resources.displayMetrics.density).roundToInt()
        }
    }
}
