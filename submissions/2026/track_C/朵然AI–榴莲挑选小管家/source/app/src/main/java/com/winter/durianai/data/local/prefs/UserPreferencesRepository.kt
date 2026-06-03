package com.winter.durianai.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// DataStore instance
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

class UserPreferencesRepository(private val context: Context) {

    private val dataStore = context.dataStore

    companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode") // auto|light|dark
        val LANGUAGE = stringPreferencesKey("language") // system|zh|en
        val DEV_MODE = booleanPreferencesKey("dev_mode")
        val LLM_TEMPERATURE = stringPreferencesKey("llm_temperature")
        val LLM_TOP_P = stringPreferencesKey("llm_top_p")
        val LLM_TOP_K = intPreferencesKey("llm_top_k")
        val LLM_BACKEND = stringPreferencesKey("llm_backend") // cpu|gpu

        val REPORT_TOTAL_COUNT = intPreferencesKey("report_total_count")
        val REPORT_LOW_COUNT = intPreferencesKey("report_low_count")
        val REPORT_LEVEL_1_COUNT = intPreferencesKey("report_level_1_count")
        val REPORT_STREAK_DAYS = intPreferencesKey("report_streak_days")
        val REPORT_LAST_DAY = stringPreferencesKey("report_last_day")
        val REPORT_LAST_SCORE = intPreferencesKey("report_last_score")
        val REPORT_LAST_LEVEL = intPreferencesKey("report_last_level")
        val REPORT_LAST_TIMESTAMP = longPreferencesKey("report_last_timestamp")

        val ACTIVE_MODEL_PATH = stringPreferencesKey("active_model_path")
        val MODEL_LAST_DETECT_HAS_MODEL = booleanPreferencesKey("model_last_detect_has_model")

        val WIDGET_DAILY_ADVICE = stringPreferencesKey("widget_daily_advice")
        val WIDGET_DAILY_REMINDER = stringPreferencesKey("widget_daily_reminder")
        val WIDGET_DAILY_UPDATED_AT = longPreferencesKey("widget_daily_updated_at")
        val WIDGET_LATEST_REPORT_SCORE = intPreferencesKey("widget_latest_report_score")
        val WIDGET_LATEST_REPORT_LEVEL = intPreferencesKey("widget_latest_report_level")
        val WIDGET_LATEST_REPORT_VARIETY = stringPreferencesKey("widget_latest_report_variety")
        val WIDGET_LATEST_REPORT_SUGGESTION = stringPreferencesKey("widget_latest_report_suggestion")
        val WIDGET_LATEST_REPORT_UPDATED_AT = longPreferencesKey("widget_latest_report_updated_at")
        val BADGE_NOTIFIED_IDS = stringPreferencesKey("badge_notified_ids")
    }

    val themeMode: Flow<String> = dataStore.data
        .map { preferences -> preferences[THEME_MODE] ?: "auto" }

    val language: Flow<String> = dataStore.data
        .map { preferences -> preferences[LANGUAGE] ?: "system" }

    val devMode: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[DEV_MODE] ?: true }

    val llmTemperature: Flow<Double> = dataStore.data
        .map { preferences -> preferences[LLM_TEMPERATURE]?.toDoubleOrNull() ?: 0.7 }

    val llmTopP: Flow<Double> = dataStore.data
        .map { preferences -> preferences[LLM_TOP_P]?.toDoubleOrNull() ?: 0.95 }

    val llmTopK: Flow<Int> = dataStore.data
        .map { preferences -> preferences[LLM_TOP_K] ?: 10 }

    val llmBackend: Flow<String> = dataStore.data
        .map { preferences -> preferences[LLM_BACKEND] ?: "cpu" }

    val reportTotalCount: Flow<Int> = dataStore.data
        .map { preferences -> preferences[REPORT_TOTAL_COUNT] ?: 0 }

    val reportLowCount: Flow<Int> = dataStore.data
        .map { preferences -> preferences[REPORT_LOW_COUNT] ?: 0 }

    val reportLevel1Count: Flow<Int> = dataStore.data
        .map { preferences -> preferences[REPORT_LEVEL_1_COUNT] ?: 0 }

    val reportStreakDays: Flow<Int> = dataStore.data
        .map { preferences -> preferences[REPORT_STREAK_DAYS] ?: 0 }

    val reportLastScore: Flow<Int?> = dataStore.data
        .map { preferences -> preferences[REPORT_LAST_SCORE] }

    val reportLastLevel: Flow<Int?> = dataStore.data
        .map { preferences -> preferences[REPORT_LAST_LEVEL] }

    val reportLastTimestamp: Flow<Long?> = dataStore.data
        .map { preferences -> preferences[REPORT_LAST_TIMESTAMP] }

    val activeModelPath: Flow<String?> = dataStore.data
        .map { preferences -> preferences[ACTIVE_MODEL_PATH] }

    val modelLastDetectHasModel: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[MODEL_LAST_DETECT_HAS_MODEL] ?: false }

    val widgetDailyAdvice: Flow<String?> = dataStore.data
        .map { preferences -> preferences[WIDGET_DAILY_ADVICE] }

    val widgetDailyReminder: Flow<String?> = dataStore.data
        .map { preferences -> preferences[WIDGET_DAILY_REMINDER] }

    val widgetDailyUpdatedAt: Flow<Long?> = dataStore.data
        .map { preferences -> preferences[WIDGET_DAILY_UPDATED_AT] }

    val widgetLatestReportScore: Flow<Int?> = dataStore.data
        .map { preferences -> preferences[WIDGET_LATEST_REPORT_SCORE] }

    val widgetLatestReportLevel: Flow<Int?> = dataStore.data
        .map { preferences -> preferences[WIDGET_LATEST_REPORT_LEVEL] }

    val widgetLatestReportVariety: Flow<String?> = dataStore.data
        .map { preferences -> preferences[WIDGET_LATEST_REPORT_VARIETY] }

    val widgetLatestReportSuggestion: Flow<String?> = dataStore.data
        .map { preferences -> preferences[WIDGET_LATEST_REPORT_SUGGESTION] }

    val widgetLatestReportUpdatedAt: Flow<Long?> = dataStore.data
        .map { preferences -> preferences[WIDGET_LATEST_REPORT_UPDATED_AT] }

    val badgeNotifiedIds: Flow<Set<String>> = dataStore.data
        .map { preferences ->
            preferences[BADGE_NOTIFIED_IDS]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
        }

    suspend fun setThemeMode(mode: String) {
        dataStore.edit { preferences ->
            preferences[THEME_MODE] = mode
        }
    }

    suspend fun setLanguage(language: String) {
        dataStore.edit { preferences ->
            preferences[LANGUAGE] = language
        }
    }

    suspend fun setDevMode(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[DEV_MODE] = enabled
        }
    }

    suspend fun setLlmTemperature(value: Double) {
        dataStore.edit { preferences ->
            preferences[LLM_TEMPERATURE] = value.toString()
        }
    }

    suspend fun setLlmTopP(value: Double) {
        dataStore.edit { preferences ->
            preferences[LLM_TOP_P] = value.toString()
        }
    }

    suspend fun setLlmTopK(value: Int) {
        dataStore.edit { preferences ->
            preferences[LLM_TOP_K] = value
        }
    }

    suspend fun setLlmBackend(value: String) {
        dataStore.edit { preferences ->
            preferences[LLM_BACKEND] = value.lowercase()
        }
    }

    suspend fun setActiveModelPath(value: String?) {
        dataStore.edit { preferences ->
            if (value.isNullOrBlank()) preferences.remove(ACTIVE_MODEL_PATH)
            else preferences[ACTIVE_MODEL_PATH] = value
        }
    }

    suspend fun setModelLastDetectHasModel(value: Boolean) {
        dataStore.edit { preferences ->
            preferences[MODEL_LAST_DETECT_HAS_MODEL] = value
        }
    }

    suspend fun recordReport(score: Int, level: Int, timestamp: Long = System.currentTimeMillis()) {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val dayStr = day.toString()

        dataStore.edit { preferences ->
            val total = (preferences[REPORT_TOTAL_COUNT] ?: 0) + 1
            val low = (preferences[REPORT_LOW_COUNT] ?: 0) + if (level >= 4) 1 else 0
            val level1 = (preferences[REPORT_LEVEL_1_COUNT] ?: 0) + if (level == 1) 1 else 0

            val previousDayStr = preferences[REPORT_LAST_DAY]
            val previousDay = previousDayStr?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
            }

            val streak = run {
                val current = preferences[REPORT_STREAK_DAYS] ?: 0
                when {
                    previousDay == null -> 1
                    previousDay == day -> current.coerceAtLeast(1)
                    previousDay == day.minusDays(1) -> (current.coerceAtLeast(1) + 1)
                    else -> 1
                }
            }

            preferences[REPORT_TOTAL_COUNT] = total
            preferences[REPORT_LOW_COUNT] = low
            preferences[REPORT_LEVEL_1_COUNT] = level1
            preferences[REPORT_STREAK_DAYS] = streak
            preferences[REPORT_LAST_DAY] = dayStr
            preferences[REPORT_LAST_SCORE] = score
            preferences[REPORT_LAST_LEVEL] = level
            preferences[REPORT_LAST_TIMESTAMP] = timestamp
        }
    }

    suspend fun setDailyAdviceWidgetContent(
        advice: String,
        reminder: String,
        updatedAt: Long = System.currentTimeMillis()
    ) {
        dataStore.edit { preferences ->
            preferences[WIDGET_DAILY_ADVICE] = advice
            preferences[WIDGET_DAILY_REMINDER] = reminder
            preferences[WIDGET_DAILY_UPDATED_AT] = updatedAt
        }
    }

    suspend fun setLatestReportWidgetContent(
        score: Int,
        level: Int,
        variety: String,
        suggestion: String,
        updatedAt: Long = System.currentTimeMillis()
    ) {
        dataStore.edit { preferences ->
            preferences[WIDGET_LATEST_REPORT_SCORE] = score
            preferences[WIDGET_LATEST_REPORT_LEVEL] = level
            preferences[WIDGET_LATEST_REPORT_VARIETY] = variety
            preferences[WIDGET_LATEST_REPORT_SUGGESTION] = suggestion
            preferences[WIDGET_LATEST_REPORT_UPDATED_AT] = updatedAt
        }
    }

    suspend fun markBadgeNotified(id: String) {
        if (id.isBlank()) return
        dataStore.edit { preferences ->
            val current = preferences[BADGE_NOTIFIED_IDS]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.toMutableSet()
                ?: mutableSetOf()
            current += id
            preferences[BADGE_NOTIFIED_IDS] = current.joinToString(",")
        }
    }
}
