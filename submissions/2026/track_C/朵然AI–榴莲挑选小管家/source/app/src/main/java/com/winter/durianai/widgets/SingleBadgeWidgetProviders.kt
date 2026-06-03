package com.winter.durianai.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.winter.durianai.MainActivity
import com.winter.durianai.R
import com.winter.durianai.data.local.prefs.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal object SingleBadgeWidgetRenderer {
    fun updateAll(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        badgeIndex: Int
    ) {
        if (appWidgetIds.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = UserPreferencesRepository(context)
            val totalReports = prefs.reportTotalCount.first()
            val lowCount = prefs.reportLowCount.first()
            val streakDays = prefs.reportStreakDays.first()
            val level1Count = prefs.reportLevel1Count.first()

            val earned = listOf(
                streakDays >= 3,
                lowCount >= 1,
                level1Count >= 5,
                totalReports >= 10,
                totalReports >= 20
            )

            val badgeDrawables = listOf(
                R.drawable.meiriyijian,
                R.drawable.paileixianfeng,
                R.drawable.pinzhibushou,
                R.drawable.tujiandaren,
                R.drawable.kuangreguofen
            )

            val clickIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("open_widgets", true)
            }
            val clickPendingIntent = PendingIntent.getActivity(
                context,
                100 + badgeIndex,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val isEarned = earned.getOrNull(badgeIndex) == true
            val drawable = badgeDrawables.getOrNull(badgeIndex) ?: R.drawable.meiriyijian

            appWidgetIds.forEach { appWidgetId ->
                val views = RemoteViews(context.packageName, R.layout.widget_badge_single)
                views.setImageViewResource(R.id.widget_badge_image, drawable)
                if (isEarned) {
                    views.setInt(R.id.widget_badge_image, "setImageAlpha", 255)
                    views.setViewVisibility(R.id.widget_lock_overlay, View.GONE)
                } else {
                    views.setInt(R.id.widget_badge_image, "setImageAlpha", 160)
                    views.setViewVisibility(R.id.widget_lock_overlay, View.VISIBLE)
                }
                views.setOnClickPendingIntent(R.id.widget_root, clickPendingIntent)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}

class Badge1WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        SingleBadgeWidgetRenderer.updateAll(context, appWidgetManager, appWidgetIds, badgeIndex = 0)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            Intent.ACTION_CONFIGURATION_CHANGED,
            Intent.ACTION_LOCALE_CHANGED -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, Badge1WidgetProvider::class.java))
                SingleBadgeWidgetRenderer.updateAll(context, manager, ids, badgeIndex = 0)
            }
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, Badge1WidgetProvider::class.java))
            SingleBadgeWidgetRenderer.updateAll(context, manager, ids, badgeIndex = 0)
        }
    }
}

class Badge2WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        SingleBadgeWidgetRenderer.updateAll(context, appWidgetManager, appWidgetIds, badgeIndex = 1)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            Intent.ACTION_CONFIGURATION_CHANGED,
            Intent.ACTION_LOCALE_CHANGED -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, Badge2WidgetProvider::class.java))
                SingleBadgeWidgetRenderer.updateAll(context, manager, ids, badgeIndex = 1)
            }
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, Badge2WidgetProvider::class.java))
            SingleBadgeWidgetRenderer.updateAll(context, manager, ids, badgeIndex = 1)
        }
    }
}

class Badge3WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        SingleBadgeWidgetRenderer.updateAll(context, appWidgetManager, appWidgetIds, badgeIndex = 2)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            Intent.ACTION_CONFIGURATION_CHANGED,
            Intent.ACTION_LOCALE_CHANGED -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, Badge3WidgetProvider::class.java))
                SingleBadgeWidgetRenderer.updateAll(context, manager, ids, badgeIndex = 2)
            }
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, Badge3WidgetProvider::class.java))
            SingleBadgeWidgetRenderer.updateAll(context, manager, ids, badgeIndex = 2)
        }
    }
}

class Badge4WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        SingleBadgeWidgetRenderer.updateAll(context, appWidgetManager, appWidgetIds, badgeIndex = 3)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            Intent.ACTION_CONFIGURATION_CHANGED,
            Intent.ACTION_LOCALE_CHANGED -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, Badge4WidgetProvider::class.java))
                SingleBadgeWidgetRenderer.updateAll(context, manager, ids, badgeIndex = 3)
            }
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, Badge4WidgetProvider::class.java))
            SingleBadgeWidgetRenderer.updateAll(context, manager, ids, badgeIndex = 3)
        }
    }
}

class Badge5WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        SingleBadgeWidgetRenderer.updateAll(context, appWidgetManager, appWidgetIds, badgeIndex = 4)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            Intent.ACTION_CONFIGURATION_CHANGED,
            Intent.ACTION_LOCALE_CHANGED -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, Badge5WidgetProvider::class.java))
                SingleBadgeWidgetRenderer.updateAll(context, manager, ids, badgeIndex = 4)
            }
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, Badge5WidgetProvider::class.java))
            SingleBadgeWidgetRenderer.updateAll(context, manager, ids, badgeIndex = 4)
        }
    }
}
