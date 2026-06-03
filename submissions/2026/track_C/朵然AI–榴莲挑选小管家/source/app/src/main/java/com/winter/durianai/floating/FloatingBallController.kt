package com.winter.durianai.floating

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.winter.durianai.R

object FloatingBallController {
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun launchFromContext(context: Context): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        context.startService(Intent(context, FloatingBallService::class.java))
        return true
    }

    fun launchFrom(activity: Activity): Boolean {
        if (!Settings.canDrawOverlays(activity)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
            Toast.makeText(activity, R.string.floating_ball_permission_toast, Toast.LENGTH_LONG).show()
            return false
        }

        activity.startService(Intent(activity, FloatingBallService::class.java))
        activity.moveTaskToBack(true)
        return true
    }
}
