package com.winter.durianai.ui.screens.agent.models

import com.winter.durianai.domain.model.DurianParameters

enum class InputFormMode {
    Active,
    History
}

enum class InputFormStatus {
    Pending,
    Analyzing,
    Done
}

enum class AnalysisStepStatus {
    Pending,
    Running,
    Done,
    Cancelled,
    Failed
}

data class AnalysisStep(
    val key: String,
    val title: String,
    val status: AnalysisStepStatus = AnalysisStepStatus.Pending,
    val detail: String? = null
)

sealed class ChatMessage {
    abstract val id: String
    abstract val timestamp: Long
    abstract val isFromUser: Boolean

    data class TextMessage(
        override val id: String,
        val text: String,
        override val isFromUser: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class ImageStripMessage(
        override val id: String,
        val imageResIds: List<Int> = emptyList(),
        val imagePaths: List<String> = emptyList(),
        val label: String? = null,
        val showDoranCheckAction: Boolean = false,
        override val isFromUser: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class AudioMessage(
        override val id: String,
        val audioPath: String,
        val durationMs: Long,
        val prompt: String? = null,
        override val isFromUser: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class CameraWidgetMessage(
        override val id: String,
        override val isFromUser: Boolean = false,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class InputFormWidgetMessage(
        override val id: String,
        val params: DurianParameters = DurianParameters(),
        val mode: InputFormMode = InputFormMode.Active,
        val status: InputFormStatus = InputFormStatus.Pending,
        override val isFromUser: Boolean = false,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class ResultReportMessage(
        override val id: String,
        val paramsSnapshot: DurianParameters = DurianParameters(),
        val imageResIds: List<Int> = emptyList(),
        val imagePaths: List<String> = emptyList(),
        val score: Int,
        val level: Int,
        val reportText: String,
        override val isFromUser: Boolean = false,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class BadgeUnlockedMessage(
        override val id: String,
        val badgeId: String,
        val title: String,
        val description: String,
        override val isFromUser: Boolean = false,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class AnalysisProgressMessage(
        override val id: String,
        val title: String = "视觉评测分析",
        val overallProgress: Float = 0f,
        val currentStepTitle: String? = null,
        val steps: List<AnalysisStep> = emptyList(),
        val interim: Map<String, String> = emptyMap(),
        val canCancel: Boolean = true,
        override val isFromUser: Boolean = false,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class ActionMessage(
        override val id: String,
        val actionType: String,
        val label: String,
        override val isFromUser: Boolean = false,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class ToolCallMessage(
        override val id: String,
        val toolName: String,
        val argsSummary: String,
        override val isFromUser: Boolean = false,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class UiCardMessage(
        override val id: String,
        val cardType: String,
        val title: String,
        val body: String,
        val bullets: List<String> = emptyList(),
        override val isFromUser: Boolean = false,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class DevLogMessage(
        override val id: String,
        val title: String,
        val detail: String,
        val isError: Boolean = false,
        override val isFromUser: Boolean = false,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()
}
