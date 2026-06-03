package com.winter.durianai.floating

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import com.winter.durianai.MainActivity
import kotlin.math.hypot

class FloatingBallService : Service() {
    private var windowManager: WindowManager? = null
    private var floatingBallView: FloatingBallView? = null
    private var trashTargetView: FloatingTrashTargetView? = null
    private var trashTargetParams: WindowManager.LayoutParams? = null
    private var trashTargetActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        showFloatingBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (floatingBallView == null) {
            showFloatingBall()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        floatingBallView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        trashTargetView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        floatingBallView = null
        trashTargetView = null
        trashTargetParams = null
        windowManager = null
        super.onDestroy()
    }

    private fun showFloatingBall() {
        if (floatingBallView != null) return

        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ballSize = FloatingBallView.defaultSizePx(this)
        val margin = FloatingBallView.edgeMarginPx(this)
        val screenWidth = manager.currentWindowMetrics.bounds.width()
        val params = WindowManager.LayoutParams(
            ballSize,
            ballSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - ballSize - margin
            y = FloatingBallView.initialTopOffsetPx(this@FloatingBallService)
        }

        val view = FloatingBallView(
            context = this,
            windowManager = manager,
            layoutParams = params,
            onClick = ::openApp,
            onDragStart = ::showTrashTarget,
            onDragMove = ::updateTrashTarget,
            onDragEnd = ::finishTrashTarget,
            onClose = ::stopSelf
        )

        windowManager = manager
        floatingBallView = view
        manager.addView(view, params)
    }

    private fun showTrashTarget() {
        val manager = windowManager ?: return
        if (trashTargetView != null) return

        val targetSize = FloatingTrashTargetView.defaultSizePx(this)
        val screenBounds = manager.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(
            targetSize,
            targetSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenBounds.width() - targetSize) / 2
            y = screenBounds.height() - targetSize - FloatingTrashTargetView.bottomMarginPx(this@FloatingBallService)
        }

        val view = FloatingTrashTargetView(this).apply {
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
        }
        trashTargetView = view
        trashTargetParams = params
        trashTargetActive = false
        manager.addView(view, params)
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140L).start()
    }

    private fun updateTrashTarget(centerX: Float, centerY: Float): Boolean {
        val active = isInsideTrashTarget(centerX, centerY)
        trashTargetActive = active
        trashTargetView?.setActive(active)
        return active
    }

    private fun finishTrashTarget(centerX: Float, centerY: Float): Boolean {
        val shouldClose = isInsideTrashTarget(centerX, centerY)
        hideTrashTarget()
        return shouldClose
    }

    private fun hideTrashTarget() {
        val manager = windowManager ?: return
        val view = trashTargetView ?: return
        view.animate()
            .alpha(0f)
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(120L)
            .withEndAction {
                runCatching { manager.removeView(view) }
                if (trashTargetView === view) {
                    trashTargetView = null
                    trashTargetParams = null
                    trashTargetActive = false
                }
            }
            .start()
    }

    private fun isInsideTrashTarget(centerX: Float, centerY: Float): Boolean {
        val params = trashTargetParams ?: return false
        val size = FloatingTrashTargetView.defaultSizePx(this)
        val targetCenterX = params.x + size / 2f
        val targetCenterY = params.y + size / 2f
        return hypot(centerX - targetCenterX, centerY - targetCenterY) <= size * 0.72f
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(intent)
    }
}
