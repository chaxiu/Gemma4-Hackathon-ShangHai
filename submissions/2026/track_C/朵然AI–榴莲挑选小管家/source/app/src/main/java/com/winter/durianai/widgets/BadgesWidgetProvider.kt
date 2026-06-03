package com.winter.durianai.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.winter.durianai.MainActivity
import com.winter.durianai.R
import com.winter.durianai.data.local.prefs.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class BadgesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            Intent.ACTION_CONFIGURATION_CHANGED,
            Intent.ACTION_LOCALE_CHANGED -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, BadgesWidgetProvider::class.java))
                updateAll(context, manager, ids)
            }
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BadgesWidgetProvider::class.java))
            updateAll(context, manager, ids)
        }

        fun updateAll(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
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

                val badges = listOf(
                    R.id.badge_1 to R.drawable.meiriyijian,
                    R.id.badge_2 to R.drawable.paileixianfeng,
                    R.id.badge_3 to R.drawable.pinzhibushou,
                    R.id.badge_4 to R.drawable.tujiandaren,
                    R.id.badge_5 to R.drawable.kuangreguofen
                )

                val clickIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("open_widgets", true)
                }
                val clickPendingIntent = PendingIntent.getActivity(
                    context,
                    1,
                    clickIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                appWidgetIds.forEach { appWidgetId ->
                    val views = RemoteViews(context.packageName, R.layout.widget_badges)
                    badges.forEachIndexed { index, (viewId, drawableRes) ->
                        views.setImageViewResource(viewId, drawableRes)
                        if (earned[index]) {
                            views.setInt(viewId, "setImageAlpha", 255)
                        } else {
                            views.setInt(viewId, "setImageAlpha", 140)
                        }
                    }
                    views.setOnClickPendingIntent(R.id.widget_root, clickPendingIntent)
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            }
        }
    }
}
