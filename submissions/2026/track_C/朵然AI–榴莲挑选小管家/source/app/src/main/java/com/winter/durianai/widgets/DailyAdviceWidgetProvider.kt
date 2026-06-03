package com.winter.durianai.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.winter.durianai.MainActivity
import com.winter.durianai.R
import com.winter.durianai.data.local.prefs.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class DailyAdviceWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        schedulePeriodicUpdates(context)
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        schedulePeriodicUpdates(context)
        updateAll(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            Intent.ACTION_CONFIGURATION_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, DailyAdviceWidgetProvider::class.java))
                updateAll(context, manager, ids)
            }
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "doran_daily_advice_widget"
        private const val UNIQUE_WORK_NOW = "doran_daily_advice_widget_now"

        fun schedulePeriodicUpdates(context: Context) {
            val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
            val request = PeriodicWorkRequestBuilder<DailyAdviceWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            manager.enqueueUniqueWork(
                UNIQUE_WORK_NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DailyAdviceWorker>().build()
            )
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DailyAdviceWidgetProvider::class.java))
            updateAll(context, manager, ids)
        }

        fun updateAll(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
            if (appWidgetIds.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch {
                val prefs = UserPreferencesRepository(context)
                val advice = prefs.widgetDailyAdvice.first() ?: context.getString(R.string.widget_daily_fallback_advice)
                val reminder = prefs.widgetDailyReminder.first() ?: context.getString(R.string.widget_daily_fallback_reminder)
                val updatedAt = prefs.widgetDailyUpdatedAt.first()
                val reportScore = prefs.widgetLatestReportScore.first()
                val reportLevel = prefs.widgetLatestReportLevel.first()
                val reportVariety = prefs.widgetLatestReportVariety.first()
                val reportSuggestion = prefs.widgetLatestReportSuggestion.first()
                val reportUpdatedAt = prefs.widgetLatestReportUpdatedAt.first()

                val showReport = reportScore != null && reportLevel != null
                val displayAdvice = if (showReport) {
                    "最近评分 ${reportScore}分 · Level ${reportLevel} · ${reportVariety ?: "未标注品种"}"
                } else {
                    advice
                }
                val displayReminder = if (showReport) {
                    reportSuggestion?.takeIf { it.isNotBlank() } ?: reminder
                } else {
                    reminder
                }
                val displayUpdatedAt = reportUpdatedAt ?: updatedAt

                val updatedText = if (displayUpdatedAt != null && displayUpdatedAt > 0L) {
                    val time = DateFormat.getTimeFormat(context).format(displayUpdatedAt)
                    "· $time"
                } else {
                    ""
                }

                val clickIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("open_widgets", true)
                }
                val clickPendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    clickIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                appWidgetIds.forEach { appWidgetId ->
                    val views = RemoteViews(context.packageName, R.layout.widget_daily_advice)
                    views.setTextViewText(R.id.widget_advice, displayAdvice)
                    views.setTextViewText(R.id.widget_reminder, displayReminder)
                    views.setTextViewText(R.id.widget_updated, updatedText)
                    views.setOnClickPendingIntent(R.id.widget_root, clickPendingIntent)
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            }
        }
    }
}
