package com.winter.durianai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.winter.durianai.data.local.prefs.UserPreferencesRepository
import com.winter.durianai.data.remote.llm.LlmCallLog
import com.winter.durianai.data.remote.llm.LlmCallLogger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ThemeMode {
    Auto,
    Light,
    Dark
}

enum class AppLanguage {
    System,
    Zh,
    En
}

class AppViewModel(
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {
    private val _devLogsSnapshot = MutableStateFlow<List<LlmCallLog>>(emptyList())
    val themeMode: StateFlow<ThemeMode> = userPreferencesRepository.themeMode
        .map {
            when (it.lowercase()) {
                "light" -> ThemeMode.Light
                "dark" -> ThemeMode.Dark
                else -> ThemeMode.Auto
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.Auto)

    val language: StateFlow<AppLanguage> = userPreferencesRepository.language
        .map {
            when (it.lowercase()) {
                "zh" -> AppLanguage.Zh
                "en" -> AppLanguage.En
                else -> AppLanguage.System
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLanguage.System)

    val devMode: StateFlow<Boolean> = userPreferencesRepository.devMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val llmTemperature: StateFlow<Double> = userPreferencesRepository.llmTemperature
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.7)

    val llmTopP: StateFlow<Double> = userPreferencesRepository.llmTopP
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.95)

    val llmTopK: StateFlow<Int> = userPreferencesRepository.llmTopK
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 10)

    val devLogs: StateFlow<List<LlmCallLog>> = _devLogsSnapshot

    init {
        viewModelScope.launch {
            LlmCallLogger.logs.collect { _devLogsSnapshot.value = it }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            userPreferencesRepository.setThemeMode(
                when (mode) {
                    ThemeMode.Auto -> "auto"
                    ThemeMode.Light -> "light"
                    ThemeMode.Dark -> "dark"
                }
            )
        }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            userPreferencesRepository.setLanguage(
                when (language) {
                    AppLanguage.System -> "system"
                    AppLanguage.Zh -> "zh"
                    AppLanguage.En -> "en"
                }
            )
        }
    }

    fun setDevMode(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setDevMode(enabled)
        }
    }

    fun setLlmTemperature(value: Double) {
        viewModelScope.launch {
            userPreferencesRepository.setLlmTemperature(value)
        }
    }

    fun setLlmTopP(value: Double) {
        viewModelScope.launch {
            userPreferencesRepository.setLlmTopP(value)
        }
    }

    fun setLlmTopK(value: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setLlmTopK(value)
        }
    }

    fun clearDevLogs() {
        LlmCallLogger.clear()
        _devLogsSnapshot.value = emptyList()
    }

    fun refreshDevLogs() {
        _devLogsSnapshot.value = LlmCallLogger.logs.value
    }
}

// Simple Factory for manual dependency injection since we don't have Hilt yet
class AppViewModelFactory(
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppViewModel(userPreferencesRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
