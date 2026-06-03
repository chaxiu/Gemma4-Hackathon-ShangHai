package com.winter.durianai.widgets

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.winter.durianai.R
import com.winter.durianai.data.local.prefs.UserPreferencesRepository
import com.winter.durianai.data.remote.llm.LlmRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DailyAdviceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = UserPreferencesRepository(applicationContext)
        val llm = LlmRepository.getInstance(applicationContext)

        val total = prefs.reportTotalCount.first()
        val low = prefs.reportLowCount.first()
        val streak = prefs.reportStreakDays.first()
        val lastScore = prefs.reportLastScore.first()
        val lastLevel = prefs.reportLastLevel.first()
        val lastTs = prefs.reportLastTimestamp.first()
        val latestSuggestion = prefs.widgetLatestReportSuggestion.first()

        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val fallbackAdvice = applicationContext.getString(R.string.widget_daily_fallback_advice)
        val fallbackReminder = applicationContext.getString(R.string.widget_daily_fallback_reminder)

        val hasModel = llm.findModelFile() != null
        val (advice, reminder) = if (!hasModel) {
            fallbackAdvice to fallbackReminder
        } else {
            val systemPrompt = """
                你是朵然（Doran AI）的“桌面小组件内容生成器”。
                你必须只输出两行中文：
                第1行以“建议：”开头；第2行以“提醒：”开头。
                每行不超过28个字，不要输出多余符号、编号、引号或解释。
            """.trimIndent()

            val userPrompt = buildString {
                append("今天日期：").append(todayStr).append('\n')
                append("累计评测次数：").append(total).append('\n')
                append("识别低出肉率次数：").append(low).append('\n')
                append("连续天数：").append(streak).append('\n')
                if (lastScore != null && lastLevel != null && lastTs != null) {
                    val dt = Instant.ofEpochMilli(lastTs).atZone(zone).toLocalDateTime()
                    append("最近一次评测：").append(dt).append(" 评分=").append(lastScore).append(" 等级=").append(lastLevel).append('\n')
                }
                if (!latestSuggestion.isNullOrBlank()) {
                    append("最新操作建议：").append(latestSuggestion).append('\n')
                }
                append("请给出今日建议与提醒。")
            }

            val raw = llm.getChatCompletion(systemPrompt, userPrompt)
            if (raw.startsWith("Error:")) {
                fallbackAdvice to fallbackReminder
            } else {
                parseTwoLines(raw, fallbackAdvice, fallbackReminder)
            }
        }

        prefs.setDailyAdviceWidgetContent(advice = advice, reminder = reminder, updatedAt = now)
        DailyAdviceWidgetProvider.updateAll(applicationContext)
        BadgesWidgetProvider.updateAll(applicationContext)
        return Result.success()
    }

    private fun parseTwoLines(raw: String, fallbackAdvice: String, fallbackReminder: String): Pair<String, String> {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val adviceLine = lines.firstOrNull { it.startsWith("建议：") } ?: lines.firstOrNull()
        val reminderLine = lines.firstOrNull { it.startsWith("提醒：") } ?: lines.drop(1).firstOrNull()

        val advice = adviceLine
            ?.removePrefix("建议：")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackAdvice

        val reminder = reminderLine
            ?.removePrefix("提醒：")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackReminder

        return advice to reminder
    }
}
