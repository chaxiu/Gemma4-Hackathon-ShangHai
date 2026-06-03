package com.winter.durianai.domain.model

enum class TaskUpdateSource {
    System,
    AgentTool,
    QuickDock,
    ChatCard,
    Camera,
    Analysis
}

enum class PhotoSetStatus {
    Missing,
    Ready,
    Invalid
}

enum class AnalysisTaskStatus {
    Idle,
    Running,
    Completed,
    Failed,
    Cancelled
}

data class PhotoQualityProfile(
    val angleLabel: String,
    val imagePath: String,
    val ok: Boolean,
    val reason: String,
    val issues: List<String> = emptyList(),
    val blurScore: Double = 0.0,
    val meanLuma: Double = 0.0,
    val stdLuma: Double = 0.0,
    val forcedUse: Boolean = false
)

data class PhotoSet(
    val imagePaths: List<String> = emptyList(),
    val status: PhotoSetStatus = PhotoSetStatus.Missing,
    val invalidReason: String? = null,
    val qualityProfiles: List<PhotoQualityProfile> = emptyList()
) {
    val count: Int get() = imagePaths.size
}

data class AnalysisTaskSnapshot(
    val status: AnalysisTaskStatus = AnalysisTaskStatus.Idle,
    val score: Int? = null,
    val level: Int? = null,
    val stage: String? = null,
    val latestReport: AnalysisReport? = null
)

data class AnalysisTrace(
    val stepKey: String,
    val toolName: String,
    val title: String,
    val output: Map<String, String> = emptyMap(),
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = System.currentTimeMillis()
)

data class ReportActionSuggestion(
    val priority: Int,
    val category: String,
    val title: String,
    val detail: String,
    val actionLabel: String? = null
)

data class AnalysisReport(
    val id: String,
    val paramsSnapshot: DurianParameters,
    val imagePaths: List<String> = emptyList(),
    val score: Int,
    val level: Int,
    val reportText: String,
    val interim: Map<String, String> = emptyMap(),
    val trace: List<AnalysisTrace> = emptyList(),
    val suggestions: List<ReportActionSuggestion> = emptyList(),
    val photoQualityProfiles: List<PhotoQualityProfile> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

data class DurianTaskState(
    val params: DurianParameters = DurianParameters(),
    val photos: PhotoSet = PhotoSet(),
    val analysis: AnalysisTaskSnapshot = AnalysisTaskSnapshot(),
    val lastUpdatedBy: TaskUpdateSource = TaskUpdateSource.System
) {
    val isReadyToAnalyze: Boolean
        get() = params.isComplete() && photos.status == PhotoSetStatus.Ready
}

data class DurianTaskPatch(
    val params: DurianParameters? = null,
    val photos: PhotoSet? = null,
    val analysis: AnalysisTaskSnapshot? = null
)

sealed class DurianTaskEvent {
    abstract val source: TaskUpdateSource
    abstract val userMessage: String?

    data class ParametersChanged(
        override val source: TaskUpdateSource,
        val changedFields: List<String>,
        val params: DurianParameters,
        override val userMessage: String? = null
    ) : DurianTaskEvent()

    data class PhotosChanged(
        override val source: TaskUpdateSource,
        val photos: PhotoSet,
        override val userMessage: String? = null
    ) : DurianTaskEvent()

    data class AnalysisChanged(
        override val source: TaskUpdateSource,
        val analysis: AnalysisTaskSnapshot,
        override val userMessage: String? = null
    ) : DurianTaskEvent()
}
