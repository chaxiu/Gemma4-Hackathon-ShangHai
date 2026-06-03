package com.winter.durianai.ui.screens.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.winter.durianai.R
import com.winter.durianai.data.local.prefs.UserPreferencesRepository
import com.winter.durianai.data.local.session.ChatSessionStore
import com.winter.durianai.data.remote.agent.DoranAdkAgent
import com.winter.durianai.data.remote.agent.DoranAdkAction
import com.winter.durianai.data.remote.agent.DoranAdkAnalysisWorkflow
import com.winter.durianai.data.remote.agent.DoranNavigationTarget
import com.winter.durianai.data.remote.agent.DoranAdkRunResult
import com.winter.durianai.data.remote.agent.DoranAnalysisWorkflowEvent
import com.winter.durianai.data.remote.llm.AudioLlmClient
import com.winter.durianai.data.remote.llm.GpuCapability
import com.winter.durianai.data.remote.llm.LlmRepository
import com.winter.durianai.data.remote.llm.LlmCallLogger
import com.winter.durianai.domain.model.AnalysisTaskSnapshot
import com.winter.durianai.domain.model.AnalysisTaskStatus
import com.winter.durianai.domain.model.AnalysisReport
import com.winter.durianai.domain.model.AnalysisTrace
import com.winter.durianai.domain.model.DurianParameters
import com.winter.durianai.domain.model.DurianShape
import com.winter.durianai.domain.model.DurianTaskEvent
import com.winter.durianai.domain.model.DurianTaskPatch
import com.winter.durianai.domain.model.DurianTaskState
import com.winter.durianai.domain.model.DurianVariety
import com.winter.durianai.domain.model.DurianVarietyProfiles
import com.winter.durianai.domain.model.PhotoSet
import com.winter.durianai.domain.model.PhotoSetStatus
import com.winter.durianai.domain.model.PhotoQualityProfile
import com.winter.durianai.domain.model.ReportActionSuggestion
import com.winter.durianai.domain.model.TaskUpdateSource
import com.winter.durianai.ui.screens.agent.models.AnalysisStep
import com.winter.durianai.ui.screens.agent.models.AnalysisStepStatus
import com.winter.durianai.ui.screens.agent.models.ChatMessage
import com.winter.durianai.ui.screens.agent.models.InputFormMode
import com.winter.durianai.ui.screens.agent.models.InputFormStatus
import com.winter.durianai.widgets.Badge1WidgetProvider
import com.winter.durianai.widgets.Badge2WidgetProvider
import com.winter.durianai.widgets.Badge3WidgetProvider
import com.winter.durianai.widgets.Badge4WidgetProvider
import com.winter.durianai.widgets.Badge5WidgetProvider
import com.winter.durianai.widgets.BadgesWidgetProvider
import com.winter.durianai.widgets.DailyAdviceWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = defaultSessionTitle(),
    val avatarPath: String? = null,
    val params: DurianParameters = DurianParameters(),
    val task: DurianTaskState = DurianTaskState(params = params),
    val messages: List<ChatMessage> = emptyList()
)

sealed class AgentChatNavigationEvent {
    object OpenHistory : AgentChatNavigationEvent()
    object OpenModelManager : AgentChatNavigationEvent()
    object OpenSettings : AgentChatNavigationEvent()
    object OpenProfile : AgentChatNavigationEvent()
    object OpenStats : AgentChatNavigationEvent()
    object OpenWidgets : AgentChatNavigationEvent()
    object OpenAbout : AgentChatNavigationEvent()
    data class OpenReport(val sessionId: String) : AgentChatNavigationEvent()
}

private fun defaultSessionTitle(): String {
    return "挑一个榴莲 ${LocalDate.now().format(DateTimeFormatter.ofPattern("M/d"))}"
}

class AgentChatViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val llmRepository = LlmRepository.getInstance(application)
    private val audioLlmClient = AudioLlmClient(application)
    private val doranAdkAgent = DoranAdkAgent(llmRepository)
    private val userPreferencesRepository = UserPreferencesRepository(application)
    private val sessionStore = ChatSessionStore(application)

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _currentParamsFlow = MutableStateFlow(DurianParameters())
    val currentParamsFlow: StateFlow<DurianParameters> = _currentParamsFlow.asStateFlow()

    private val _isModelLoading = MutableStateFlow(false)
    val isModelLoading: StateFlow<Boolean> = _isModelLoading.asStateFlow()

    private val _isEngineReady = MutableStateFlow(false)
    val isEngineReady: StateFlow<Boolean> = _isEngineReady.asStateFlow()

    private val _preferredBackend = MutableStateFlow("cpu")
    val preferredBackend: StateFlow<String> = _preferredBackend.asStateFlow()

    private val _engineBackendUsed = MutableStateFlow<String?>(null)
    val engineBackendUsed: StateFlow<String?> = _engineBackendUsed.asStateFlow()

    private val _gpuCapability = MutableStateFlow<GpuCapability?>(null)
    val gpuCapability: StateFlow<GpuCapability?> = _gpuCapability.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()
    private val _isReportAnalyzing = MutableStateFlow(false)
    val isReportAnalyzing: StateFlow<Boolean> = _isReportAnalyzing.asStateFlow()
    private val _analysisStage = MutableStateFlow("准备分析")
    val analysisStage: StateFlow<String> = _analysisStage.asStateFlow()

    private val _visionCaptureEvent = MutableStateFlow(0L)
    val visionCaptureEvent: StateFlow<Long> = _visionCaptureEvent.asStateFlow()
    private val _pendingVisionSeedImagePath = MutableStateFlow<String?>(null)
    val pendingVisionSeedImagePath: StateFlow<String?> = _pendingVisionSeedImagePath.asStateFlow()
    private val _navigationEvents = MutableSharedFlow<AgentChatNavigationEvent>(extraBufferCapacity = 8)
    val navigationEvents = _navigationEvents.asSharedFlow()

    private val _currentParamsState = MutableStateFlow(DurianParameters())
    val currentParamsState: StateFlow<DurianParameters> = _currentParamsState.asStateFlow()

    private val _currentTaskState = MutableStateFlow(DurianTaskState())
    val currentTaskState: StateFlow<DurianTaskState> = _currentTaskState.asStateFlow()
    private val _taskEvents = MutableSharedFlow<DurianTaskEvent>(extraBufferCapacity = 16)
    val taskEvents = _taskEvents.asSharedFlow()

    // Internal state tracker for parameters of the current session
    private var currentParams = DurianParameters()
    private var currentTask = DurianTaskState()
    private var isAnalyzing = false
    private val modelInitTimeoutMs = 120_000L
    private val analysisStages = listOf(
        "种类识别对比（若用户已选，以用户为准）",
        "刺特征分析",
        "密度估计",
        "房数评估",
        "重量核验",
        "品质综合判断"
    )
    private val initialGreetingText =
        "您好呀！👋 我是朵然 (Doran AI)，懂你的榴莲小管家。\n\n你可以直接发给我榴莲的照片📷，或者告诉我它的重量和房数，我会帮你进行全方位的出肉率分析哦~\n\n随时跟我说“挑选其他榴莲”就可以开启新流程啦✨。"

    private var engineInitJob: Job? = null
    private var lastAskedFormId: String? = null
    private var activeFormId: String? = null
    private var analyzingFormId: String? = null
    private var analysisProgressJob: Job? = null
    private var persistSessionsJob: Job? = null
    private var analysisProgressMessageId: String? = null
    private var prePhotoDrivenMessagesSnapshot: List<ChatMessage>? = null
    private var prePhotoDrivenParamsSnapshot: DurianParameters? = null
    private var prePhotoDrivenTaskSnapshot: DurianTaskState? = null
    private var prePhotoDrivenActiveFormIdSnapshot: String? = null

    private fun applyTaskPatch(
        patch: DurianTaskPatch,
        source: TaskUpdateSource,
        shouldUpsertForm: Boolean = false,
        announce: Boolean = true,
        forceParameterEvent: Boolean = false
    ) {
        val before = currentTask
        val next = currentTask.copy(
            params = patch.params ?: currentTask.params,
            photos = patch.photos ?: currentTask.photos,
            analysis = patch.analysis ?: currentTask.analysis,
            lastUpdatedBy = source
        )
        currentTask = next
        currentParams = next.params
        _currentTaskState.value = next
        _currentParamsFlow.value = next.params
        _currentParamsState.value = next.params
        if (shouldUpsertForm) {
            upsertActiveForm()
        }
        emitTaskEvents(before, next, patch, source, announce, forceParameterEvent)
        updateCurrentSessionInList()
    }

    private fun emitTaskEvents(
        before: DurianTaskState,
        next: DurianTaskState,
        patch: DurianTaskPatch,
        source: TaskUpdateSource,
        announce: Boolean,
        forceParameterEvent: Boolean
    ) {
        if (patch.params != null && (before.params != next.params || forceParameterEvent)) {
            val changedFields = changedParameterFields(before.params, next.params)
            _taskEvents.tryEmit(
                DurianTaskEvent.ParametersChanged(
                    source = source,
                    changedFields = changedFields,
                    params = next.params,
                    userMessage = if (announce) parameterEventMessage(source, changedFields) else null
                )
            )
        }
        if (patch.photos != null && before.photos != next.photos) {
            _taskEvents.tryEmit(
                DurianTaskEvent.PhotosChanged(
                    source = source,
                    photos = next.photos,
                    userMessage = if (announce) photoEventMessage(next.photos) else null
                )
            )
        }
        if (patch.analysis != null && before.analysis != next.analysis) {
            _taskEvents.tryEmit(
                DurianTaskEvent.AnalysisChanged(
                    source = source,
                    analysis = next.analysis,
                    userMessage = null
                )
            )
        }
    }

    private fun changedParameterFields(before: DurianParameters, after: DurianParameters): List<String> {
        return buildList {
            if (before.variety != after.variety) add("品种")
            if (before.weightKg != after.weightKg) add("重量")
            if (before.largeLobes != after.largeLobes || before.smallLobes != after.smallLobes) add("房数")
            if (before.shape != after.shape) add("形态")
        }
    }

    private fun parameterEventMessage(source: TaskUpdateSource, changedFields: List<String>): String? {
        return when (source) {
            TaskUpdateSource.QuickDock -> {
                val label = changedFields.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "参数"
                "已更新$label"
            }
            TaskUpdateSource.ChatCard -> "参数已同步到当前任务"
            else -> null
        }
    }

    private fun photoEventMessage(photos: PhotoSet): String? {
        return when (photos.status) {
            PhotoSetStatus.Ready -> "五角度照片已就绪"
            PhotoSetStatus.Invalid -> "照片无效，请重新拍摄"
            PhotoSetStatus.Missing -> null
        }
    }

    private fun resetCurrentTask(source: TaskUpdateSource = TaskUpdateSource.System) {
        currentTask = DurianTaskState(lastUpdatedBy = source)
        currentParams = currentTask.params
        _currentTaskState.value = currentTask
        _currentParamsFlow.value = currentTask.params
        _currentParamsState.value = currentTask.params
    }

    private fun clearNotReadyHints() {
        _messages.update { list ->
            list.filterNot { msg ->
                when (msg) {
                    is ChatMessage.ActionMessage -> msg.actionType == "OPEN_MODEL_MANAGER"
                    is ChatMessage.TextMessage -> !msg.isFromUser && (
                        msg.text.contains("本机模型未准备好") ||
                            msg.text.contains("本机模型仍未准备好") ||
                            msg.text.contains("本地模型未准备")
                        )
                    else -> false
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            launch {
                userPreferencesRepository.llmBackend.collect { backend ->
                    _preferredBackend.value = backend
                }
            }
            launch(Dispatchers.Default) {
                _gpuCapability.value = llmRepository.getGpuCapability()
            }
            
            launch {
                restoreSessionsOrCreateNew()
            }
        }
    }

    private suspend fun restoreSessionsOrCreateNew() {
        val restored = withContext(Dispatchers.IO) {
            sessionStore.loadSessions()
        }
        if (restored.isNotEmpty()) {
            _sessions.value = restored
            switchSession(restored.last().id)
        } else {
            createNewSession()
        }
    }

    private fun startEngineInit(reason: String? = null, allowDuplicate: Boolean = false) {
        if (!allowDuplicate && (_isEngineReady.value || _isModelLoading.value)) return
        engineInitJob?.cancel()
        engineInitJob = viewModelScope.launch {
            _isModelLoading.value = true
            val ok = initializeEngineSafely(reason)
            _isModelLoading.value = false
            _isEngineReady.value = ok
            _engineBackendUsed.value = llmRepository.getLastInitBackendUsed()
            if (ok) {
                clearNotReadyHints()
            } else {
                val hasEntry = _messages.value.any { it is ChatMessage.ActionMessage && it.actionType == "OPEN_MODEL_MANAGER" }
                if (!hasEntry) {
                    addBotMessage(
                        buildString {
                            append("本机模型未准备好。")
                            if (!reason.isNullOrBlank()) append("\n原因：").append(reason)
                            append("\n\n你可以去【模型管理】导入模型（本地选择 / URL 下载）。\n也可以在对话页右上角点刷新，或在加载卡片里点终止后再试。")
                        }
                    )
                    _messages.update {
                        it + ChatMessage.ActionMessage(
                            id = randomString(),
                            actionType = "OPEN_MODEL_MANAGER",
                            label = "去模型管理"
                        )
                    }
                }
            }
        }
    }

    fun maybeInitEngine(reason: String? = null) {
        startEngineInit(reason = reason, allowDuplicate = false)
    }

    fun consumeVisionCaptureEvent() {
        _visionCaptureEvent.value = 0L
    }

    fun consumePendingVisionSeedImage() {
        _pendingVisionSeedImagePath.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        persistSessionsJob?.cancel()
        llmRepository.close()
    }

    fun createNewSession() {
        viewModelScope.launch {
            val newSession = ChatSession()
            _sessions.update { it + newSession }
            switchSession(newSession.id)
            
            _messages.value = emptyList()
            resetCurrentTask()
            activeFormId = null
            analyzingFormId = null
            prePhotoDrivenMessagesSnapshot = null
            prePhotoDrivenParamsSnapshot = null
            prePhotoDrivenTaskSnapshot = null
            prePhotoDrivenActiveFormIdSnapshot = null
            addInitialBotSequence()
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        val title = newTitle.trim()
        if (title.isBlank()) return
        _sessions.update { list ->
            list.map { if (it.id == sessionId) it.copy(title = title) else it }
        }
        schedulePersistSessions()
    }

    fun deleteSession(sessionId: String) {
        val deleted = _sessions.value.find { it.id == sessionId }
        val remaining = _sessions.value.filterNot { it.id == sessionId }
        _sessions.value = remaining
        cleanupSessionPhotos(deleted)
        schedulePersistSessions()

        if (_currentSessionId.value == sessionId) {
            val next = remaining.lastOrNull()
            if (next != null) {
                switchSession(next.id)
            } else {
                createNewSession()
            }
        }
    }

    fun switchSession(sessionId: String) {
        val session = _sessions.value.find { it.id == sessionId } ?: return
        
        // Save current state to the old session before switching
        _currentSessionId.value?.let { oldId ->
            _sessions.update { list ->
                list.map { 
                    if (it.id == oldId) it.copy(messages = _messages.value, params = currentParams, task = currentTask)
                    else it 
                }
            }
            schedulePersistSessions()
        }

        // Load new session
        _currentSessionId.value = sessionId
        _messages.value = session.messages
        val restoredParams = if (session.task.params == DurianParameters() && session.params != DurianParameters()) {
            session.params
        } else {
            session.task.params
        }
        val restoredPhotos = if (session.task.photos.status == PhotoSetStatus.Missing) {
            derivePhotoSetFromMessages(session.messages)
        } else {
            session.task.photos
        }
        currentTask = session.task.copy(params = restoredParams, photos = restoredPhotos)
        currentParams = currentTask.params
        _currentTaskState.value = currentTask
        _currentParamsFlow.value = currentParams
        _currentParamsState.value = currentParams
        activeFormId = session.messages.asReversed()
            .filterIsInstance<ChatMessage.InputFormWidgetMessage>()
            .firstOrNull { it.mode == InputFormMode.Active }
            ?.id
        analyzingFormId = session.messages.asReversed()
            .filterIsInstance<ChatMessage.InputFormWidgetMessage>()
            .firstOrNull { it.status == InputFormStatus.Analyzing }
            ?.id
    }

    private fun updateCurrentSessionInList() {
        val currentId = _currentSessionId.value ?: return
        _sessions.update { list ->
            list.map {
                if (it.id == currentId) it.copy(messages = _messages.value, params = currentParams, task = currentTask)
                else it
            }
        }
        schedulePersistSessions()
    }

    private fun schedulePersistSessions() {
        val snapshot = _sessions.value
        persistSessionsJob?.cancel()
        persistSessionsJob = viewModelScope.launch(Dispatchers.IO) {
            delay(150)
            sessionStore.saveSessions(snapshot)
        }
    }

    private fun cleanupSessionPhotos(session: ChatSession?) {
        if (session == null) return
        val appFilesDir = getApplication<Application>().filesDir.absolutePath
        val paths = buildSet {
            addAll(session.task.photos.imagePaths)
            session.task.photos.qualityProfiles.forEach { add(it.imagePath) }
            session.task.analysis.latestReport?.let { report ->
                addAll(report.imagePaths)
                report.photoQualityProfiles.forEach { add(it.imagePath) }
            }
            session.messages.forEach { msg ->
                when (msg) {
                    is ChatMessage.ImageStripMessage -> addAll(msg.imagePaths)
                    is ChatMessage.ResultReportMessage -> addAll(msg.imagePaths)
                    else -> Unit
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            paths
                .filter { it.startsWith(appFilesDir) && it.contains("/durian_photos/") }
                .forEach { runCatching { File(it).delete() } }
        }
    }

    private fun derivePhotoSetFromMessages(messages: List<ChatMessage>): PhotoSet {
        val lastImages = messages.asReversed().firstOrNull { it is ChatMessage.ImageStripMessage } as? ChatMessage.ImageStripMessage
            ?: return PhotoSet()
        if (lastImages.label == "图片无效") {
            return PhotoSet(status = PhotoSetStatus.Invalid, invalidReason = "图片无效")
        }
        return if (lastImages.imagePaths.size >= 5) {
            PhotoSet(imagePaths = lastImages.imagePaths, status = PhotoSetStatus.Ready)
        } else {
            PhotoSet(imagePaths = lastImages.imagePaths, status = PhotoSetStatus.Missing)
        }
    }

    fun startGreeting() {
        // Just clear current session messages and restart
        viewModelScope.launch {
            _messages.value = emptyList()
            resetCurrentTask()
            activeFormId = null
            analyzingFormId = null
            prePhotoDrivenMessagesSnapshot = null
            prePhotoDrivenParamsSnapshot = null
            prePhotoDrivenTaskSnapshot = null
            prePhotoDrivenActiveFormIdSnapshot = null
            addBotMessage("好滴，我们重新开始看这颗榴莲！\n\n你可以重新发照片或者告诉我参数哦~")
            delay(1000)
            addBotCameraWidget()
            updateCurrentSessionInList()
        }
    }

    fun ensureInitialCopyVisible() {
        viewModelScope.launch {
            if (_currentSessionId.value == null) {
                val latest = _sessions.value.lastOrNull()
                if (latest != null) {
                    switchSession(latest.id)
                } else {
                    createNewSession()
                    return@launch
                }
            }
            if (_messages.value.isEmpty()) {
                addInitialBotSequence()
            }
        }
    }

    fun onUserSendMessage(text: String) {
        if (text.isBlank() || isAnalyzing || _isReportAnalyzing.value) return
        
        val userMsg = ChatMessage.TextMessage(
            id = randomString(),
            text = text,
            isFromUser = true
        )
        _messages.update { it + userMsg }

        isAnalyzing = true
        _isThinking.value = true
        viewModelScope.launch {
            val wasReady = _isEngineReady.value
            if (!wasReady) {
                _isModelLoading.value = true
                val ok = initializeEngineSafely("发送消息")
                _isModelLoading.value = false
                _isEngineReady.value = ok

                if (ok) {
                    _messages.update { list ->
                        list.filterNot { msg ->
                            when (msg) {
                                is ChatMessage.ActionMessage -> msg.actionType == "OPEN_MODEL_MANAGER"
                                is ChatMessage.TextMessage -> !msg.isFromUser && (
                                    msg.text.contains("本机模型仍未准备好") ||
                                        msg.text.contains("本地模型未准备")
                                    )
                                else -> false
                            }
                        }
                    }
                    val activePath = llmRepository.getActiveModelPath()
                    val devModeEnabled = userPreferencesRepository.devMode.first()
                    addBotMessage(
                        buildString {
                            append("检测到本机模型，初始化完成 ✅")
                            if (devModeEnabled && !activePath.isNullOrBlank()) {
                                append("\n").append(activePath)
                            }
                        }
                    )
                } else {
                    val hasEntry = _messages.value.any { it is ChatMessage.ActionMessage && it.actionType == "OPEN_MODEL_MANAGER" }
                    if (!hasEntry) {
                        addBotMessage(
                            "本机模型仍未准备好。\n\n你可以去【模型管理】导入模型（本地选择 / URL 下载）。"
                        )
                        _messages.update {
                            it + ChatMessage.ActionMessage(
                                id = randomString(),
                                actionType = "OPEN_MODEL_MANAGER",
                                label = "去模型管理"
                            )
                        }
                    }
                }
            }

            try {
                val outcome = withContext(Dispatchers.IO) {
                    doranAdkAgent.runTurn(
                        userText = text,
                        currentParams = currentParams,
                        photoState = buildAdkPhotoState(),
                        sessionId = _currentSessionId.value ?: "default",
                        historySummary = buildAdkHistorySummary()
                    )
                }

                if (outcome.rawModelText?.startsWith("Error:") == true) {
                    maybeAppendDevErrorLog(stage = "adk_intent", errorText = outcome.rawModelText)
                }

                val shouldContinue = consumeAgentOutcome(outcome)
                if (!shouldContinue) return@launch
            } catch (e: Exception) {
                maybeAppendDevErrorLog(
                    stage = "adk_agent",
                    errorText = e.localizedMessage ?: "ADK Agent 执行失败"
                )
                addBotMessage("哎呀，刚刚网络小哥走神了\uD83D\uDE2D，能麻烦你再说一遍嘛？")
            } finally {
                isAnalyzing = false
                _isThinking.value = false
                updateCurrentSessionInList()
            }
        }
    }

    fun onUserSendAudio(audioPath: String, prompt: String = "", durationMs: Long = 0L) {
        if (audioPath.isBlank() || isAnalyzing || _isReportAnalyzing.value) return
        val audioFile = File(audioPath)
        if (!audioFile.exists()) {
            addBotMessage("这段语音文件没有保存成功，请再录一次。")
            return
        }

        _messages.update {
            it + ChatMessage.AudioMessage(
                id = randomString(),
                audioPath = audioPath,
                durationMs = durationMs,
                prompt = prompt.takeIf { value -> value.isNotBlank() },
                isFromUser = true
            )
        }

        isAnalyzing = true
        _isThinking.value = true
        viewModelScope.launch {
            try {
                if (!_isEngineReady.value) {
                    _isModelLoading.value = true
                    val ok = initializeEngineSafely("发送语音")
                    _isModelLoading.value = false
                    _isEngineReady.value = ok
                    if (!ok) {
                        addBotMessage("本机模型仍未准备好，暂时不能处理语音。\n\n请去【模型管理】导入支持音频的 Gemma4 多模态模型。")
                        return@launch
                    }
                }

                val systemPrompt = """
                    你是朵然（Doran AI），一个端侧榴莲挑选 Agent。
                    用户会直接发送一段语音给你，不要把它当成系统语音识别文本。
                    请直接理解音频内容，并用中文自然回复。
                    如果音频里包含榴莲重量、房数、品种、形态、拍照或分析意图，请明确复述你理解到的关键信息，并告诉用户下一步。
                    如果听不清，请说明需要重录。
                """.trimIndent()
                val userPrompt = buildString {
                    append("请直接听这段语音并回复用户。")
                    if (prompt.isNotBlank()) {
                        append("\n用户文字补充：").append(prompt)
                    }
                    append("\n当前任务状态：").append(buildAdkPhotoState())
                    append("\n当前参数：").append(currentParams)
                }
                val raw = withContext(Dispatchers.IO) {
                    audioLlmClient.getAudioCompletion(
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        audioPath = audioPath
                    )
                }
                if (raw.startsWith("Error:")) {
                    maybeAppendDevErrorLog(stage = "audio", errorText = raw)
                    addBotMessage("这段语音我暂时处理不了。为了避免主应用崩溃，语音识别现在已经改为独立进程执行；如果当前模型的音频多模态能力不稳定，会返回失败或超时，但不应再把 app 一起带崩。你可以去【模型管理】做健康检查，或换一个明确支持音频输入的 LiteRT-LM 模型。")
                } else {
                    addBotMessage(raw)
                }
            } catch (e: Exception) {
                maybeAppendDevErrorLog(stage = "audio", errorText = e.localizedMessage ?: "语音处理失败")
                addBotMessage("语音处理失败了，请再录一次。")
            } finally {
                isAnalyzing = false
                _isThinking.value = false
                updateCurrentSessionInList()
            }
        }
    }

    private suspend fun consumeAgentOutcome(outcome: DoranAdkRunResult): Boolean {
        if (outcome.actions.any { it is DoranAdkAction.RestartSelection }) {
            startGreeting()
            return false
        }

        val actions = outcome.actions

        actions.forEach { action ->
            if (action is DoranAdkAction.UpdateParameters && action.params != currentParams) {
                applyParamsUpdate(action.params, announce = false)
            }
        }

        appendToolCallMessages(actions)
        addBotMessage(outcome.reply)

        actions.forEach { action ->
            when (action) {
                is DoranAdkAction.RequestCameraCapture -> {
                    onCameraActionClicked()
                    val recent = _messages.value.takeLast(6)
                    if (recent.none { it is ChatMessage.CameraWidgetMessage }) {
                        addBotCameraWidget()
                    }
                }
                is DoranAdkAction.RequestInputForm -> upsertActiveForm(moveToBottom = true)
                is DoranAdkAction.StartAnalysis -> startAnalysisRequestedByAgent()
                is DoranAdkAction.Navigate -> emitNavigationEvent(action.target)
                is DoranAdkAction.ShowUiCard -> appendUiCard(action)
                is DoranAdkAction.UpdateParameters,
                is DoranAdkAction.RestartSelection -> Unit
            }
        }
        return true
    }

    fun refreshEngine() {
        llmRepository.close()
        _isEngineReady.value = false
        startEngineInit(reason = "刷新模型", allowDuplicate = true)
    }

    fun switchActiveModel(modelPath: String?) {
        viewModelScope.launch {
            userPreferencesRepository.setActiveModelPath(modelPath)
            llmRepository.close()
            _isEngineReady.value = false

            _messages.value = emptyList()
            resetCurrentTask()
            addBotMessage("已切换模型，已重置当前会话。")
            delay(400)
            addBotCameraWidget()
            updateCurrentSessionInList()
            startEngineInit(reason = "切换模型", allowDuplicate = true)
        }
    }

    fun cancelEngineInit() {
        engineInitJob?.cancel()
        engineInitJob = null
        llmRepository.close()
        _isModelLoading.value = false
        _isEngineReady.value = false
        _engineBackendUsed.value = null
        _isThinking.value = false
        isAnalyzing = false
    }

    fun invalidateEngine() {
        engineInitJob?.cancel()
        engineInitJob = null
        llmRepository.close()
        _isEngineReady.value = false
        _engineBackendUsed.value = null
        _isModelLoading.value = false
    }

    suspend fun runModelHealthCheck(): String {
        val preferredPath = userPreferencesRepository.activeModelPath.first()
        val modelFile = preferredPath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.exists() }
            ?: llmRepository.findModelFile()
            ?: return "未找到本地模型，请先导入 .litertlm 文件。"

        if (!modelFile.extension.equals("litertlm", ignoreCase = true)) {
            return "模型文件后缀异常：需要 .litertlm。"
        }
        if (modelFile.length() < 50L * 1024L * 1024L) {
            return "模型文件过小，可能不是完整 LiteRT-LM 模型：${modelFile.name}。"
        }

        _isModelLoading.value = true
        return try {
            val start = System.currentTimeMillis()
            val text = withTimeout(60_000L) {
                llmRepository.getChatCompletion(
                    systemPrompt = "你是本地模型健康检查器。只需要确认模型能否正常生成中文短文本。",
                    userPrompt = "请只回复：Doran 模型健康检查通过"
                )
            }
            _isEngineReady.value = !text.startsWith("Error:")
            _engineBackendUsed.value = llmRepository.getLastInitBackendUsed()
            val duration = System.currentTimeMillis() - start
            if (text.startsWith("Error:")) {
                "健康检查失败：${text.take(180)}"
            } else {
                "健康检查通过，用时 ${duration}ms，后端 ${llmRepository.getLastInitBackendUsed() ?: "未知"}。"
            }
        } catch (e: TimeoutCancellationException) {
            llmRepository.close()
            _isEngineReady.value = false
            "健康检查超时，模型可能过大、后端不可用或设备资源不足。"
        } catch (e: Exception) {
            _isEngineReady.value = false
            "健康检查失败：${e.localizedMessage ?: "未知错误"}"
        } finally {
            _isModelLoading.value = false
        }
    }

    suspend fun applyLlmBackend(requestedBackend: String): String {
        val requested = requestedBackend.lowercase()
        val capability = llmRepository.getGpuCapability()
        _gpuCapability.value = capability

        if (requested == "gpu" && !capability.openClRuntimeAvailable) {
            userPreferencesRepository.setLlmBackend("cpu")
            llmRepository.close()
            _isModelLoading.value = true
            val ok = initializeEngineSafely("切换 GPU")
            _isModelLoading.value = false
            _isEngineReady.value = ok
            _engineBackendUsed.value = llmRepository.getLastInitBackendUsed()
            return "当前设备缺少 OpenCL 运行时，GPU 不可用，已切回 CPU"
        }

        userPreferencesRepository.setLlmBackend(requested)
        llmRepository.close()

        _isModelLoading.value = true
        val ok = initializeEngineSafely("切换后端")
        _isModelLoading.value = false
        _isEngineReady.value = ok
        val used = llmRepository.getLastInitBackendUsed()
        _engineBackendUsed.value = used

        if (!ok) {
            userPreferencesRepository.setLlmBackend("cpu")
            return "切换失败：${llmRepository.getLastInitError() ?: "未知错误"}"
        }

        if (requested == "gpu" && used != "gpu") {
            val reason = llmRepository.getLastInitError()
                ?: llmRepository.getLastInitFallbackReason()
                ?: "未知原因"
            userPreferencesRepository.setLlmBackend("cpu")
            return "GPU 初始化失败，已回退 CPU：$reason"
        }

        return if (used == "gpu") "已切换到 GPU" else "已切换到 CPU"
    }

    private suspend fun initializeEngineSafely(reason: String? = null): Boolean {
        return try {
            withTimeout(modelInitTimeoutMs) {
                llmRepository.initializeEngine()
            }
        } catch (e: TimeoutCancellationException) {
            llmRepository.close()
            maybeAppendDevErrorLog(
                stage = "engine_timeout",
                errorText = "模型初始化超时${if (reason.isNullOrBlank()) "" else "：$reason"}"
            )
            false
        } catch (e: Exception) {
            maybeAppendDevErrorLog(
                stage = "engine_error",
                errorText = e.localizedMessage ?: "模型初始化失败"
            )
            false
        }
    }

    fun onAddImage(fromCamera: Boolean) {
        if (isAnalyzing || _isReportAnalyzing.value) return
    }

    fun onChatImageSelected(imagePath: String, sourceLabel: String) {
        if (imagePath.isBlank() || isAnalyzing || _isReportAnalyzing.value) return
        val file = File(imagePath)
        if (!file.exists()) {
            addBotMessage("这张图片没有保存成功，请重新选择一次。")
            return
        }

        _messages.update {
            it + ChatMessage.ImageStripMessage(
                id = randomString(),
                imagePaths = listOf(imagePath),
                label = sourceLabel,
                showDoranCheckAction = true,
                isFromUser = true
            )
        }

        isAnalyzing = true
        _isThinking.value = true
        viewModelScope.launch {
            try {
                if (!_isEngineReady.value) {
                    _isModelLoading.value = true
                    val ok = initializeEngineSafely("发送图片")
                    _isModelLoading.value = false
                    _isEngineReady.value = ok
                    if (!ok) {
                        addBotMessage("本机模型仍未准备好，暂时不能处理图片。\n\n请去【模型管理】导入支持视觉的 Gemma4 多模态模型。")
                        return@launch
                    }
                }

                val systemPrompt = """
                    你是朵然（Doran AI），一个端侧榴莲挑选助手。
                    用户在聊天模式发来一张图片，请直接结合图片内容进行自然中文回复。
                    这不是五角度采集流程，不要把它当成当前任务已完成的拍摄数据。
                    如果看起来像榴莲，请说明你看到了什么，以及建议下一步是继续聊天还是进入 Doran 检查。
                    如果不是榴莲或看不清，也请直说并给出下一步建议。
                """.trimIndent()
                val userPrompt = buildString {
                    append("请识别并回复这张图片。")
                    append("\n来源：").append(sourceLabel)
                    append("\n当前任务状态：").append(buildAdkPhotoState())
                    append("\n当前参数：").append(currentParams)
                }
                val raw = withContext(Dispatchers.IO) {
                    llmRepository.getVisionCompletion(
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        imagePath = imagePath
                    )
                }
                if (raw.startsWith("Error:")) {
                    maybeAppendDevErrorLog(stage = "chat_image", errorText = raw)
                    addBotMessage("图片我收到了，但这次视觉回复失败了。你可以点图片上的【Doran检查】进入视觉识别组件，或者换一张再试。")
                } else {
                    addBotMessage(raw)
                }
            } catch (e: Exception) {
                maybeAppendDevErrorLog(stage = "chat_image", errorText = e.localizedMessage ?: "图片处理失败")
                addBotMessage("这张图片处理失败了，请换一张再试。")
            } finally {
                isAnalyzing = false
                _isThinking.value = false
                updateCurrentSessionInList()
            }
        }
    }

    fun prepareVisionCheckFromChatImage(imagePath: String) {
        val file = File(imagePath)
        if (!file.exists()) {
            addBotMessage("这张图片已经找不到了，请重新发送后再试。")
            return
        }
        _pendingVisionSeedImagePath.value = imagePath
    }

    fun onCameraActionClicked() {
        _visionCaptureEvent.value = System.currentTimeMillis()
    }

    fun onVisionCaptureFinished(
        imagePaths: List<String>,
        qualityProfiles: List<PhotoQualityProfile> = emptyList()
    ) {
        viewModelScope.launch {
            _messages.update {
                it + ChatMessage.ImageStripMessage(
                    id = randomString(),
                    imagePaths = imagePaths,
                    label = "五角度照片",
                    isFromUser = true
                )
            }
            addBotMessage("收到！我拿到 5 个角度的照片啦📷")
            delay(450)
            applyTaskPatch(
                patch = DurianTaskPatch(
                    photos = PhotoSet(
                        imagePaths = imagePaths,
                        status = PhotoSetStatus.Ready,
                        qualityProfiles = qualityProfiles
                    ),
                    analysis = AnalysisTaskSnapshot(status = AnalysisTaskStatus.Idle)
                ),
                source = TaskUpdateSource.Camera
            )
            if (currentParams.isComplete()) {
                addBotMessage("照片和参数都齐了，我现在开始结合这些信息给你分析。")
                startAnalysisRequestedByAgent()
            } else {
                addBotMessage("下一步请补齐重量、房数和形态；下面的表单已经放到最底部，填好后我会自动开始分析。")
                upsertActiveForm(moveToBottom = true)
            }
        }
    }

    fun onVisionCaptureInvalid(reason: String) {
        viewModelScope.launch {
            _messages.update {
                it + ChatMessage.ImageStripMessage(
                    id = randomString(),
                    imagePaths = emptyList(),
                    label = "图片无效",
                    isFromUser = true
                )
            }
            addBotMessage("照片无效：$reason\n请重新拍摄后再继续填写参数。")
            applyTaskPatch(
                patch = DurianTaskPatch(
                    photos = PhotoSet(
                        imagePaths = emptyList(),
                        status = PhotoSetStatus.Invalid,
                        invalidReason = reason
                    ),
                    analysis = AnalysisTaskSnapshot(status = AnalysisTaskStatus.Idle)
                ),
                source = TaskUpdateSource.Camera
            )
        }
    }

    data class DoranPhotoCheck(
        val ok: Boolean,
        val reason: String,
        val issues: List<String>
    )

    suspend fun checkPhotoWithDoran(
        imagePath: String,
        stepLabel: String,
        blurScore: Double,
        meanLuma: Double,
        stdLuma: Double,
        reasons: List<String>
    ): DoranPhotoCheck {
        if (!isEngineReady.value) {
            return DoranPhotoCheck(ok = false, reason = "本地模型未就绪，无法进行图片检查", issues = listOf("other"))
        }
        return try {
            val systemPrompt =
                "你是榴莲五角度拍摄质检助手，会看到一张用户刚拍的图片。你的目标不是评价榴莲好坏，而是判断这张照片是否适合进入后续视觉分析。" +
                    "必须根据指定拍摄角度进行检查，并输出严格 JSON，不能输出任何多余文本。"
            val userPrompt = buildString {
                append("拍摄角度：").append(stepLabel).append('\n')
                append("角度要求：").append(photoAngleRequirement(stepLabel)).append('\n')
                append("请按以下硬性标准检查：\n")
                append("1) 主体必须是榴莲；不是榴莲直接 ok=false，issues 包含 non_durian。\n")
                append("2) 必须符合当前拍摄角度；例如“上面”应主要看到顶部/果肩，“下面”应看到底部/果脐/星形区域，侧面应看到对应侧轮廓，“正面”应看到完整正向轮廓。角度明显不符则 ok=false，issues 包含 wrong_angle。\n")
                append("3) 榴莲主体优先完整，但如果为了看清局部结构而近距离拍摄，只要当前角度关键区域清晰、占画面主体，允许轻微裁切；只有严重裁切、关键区域缺失、离太远或大半出画时才标记 cropped 或 too_far。\n")
                append("4) 画面不能有明显遮挡，例如手、袋子、贴纸、桌面物品、其他水果大面积挡住榴莲；否则 issues 包含 occluded。\n")
                append("5) 背景杂物不能多到影响轮廓和刺/房线判断；否则 issues 包含 cluttered。\n")
                append("6) 不能严重模糊、过暗、过曝、反光；否则使用 blurry/dark/overexposed/glare。\n")
                append("7) 轻微背景、轻微手指边缘、为了拍清底部/果脐而出现的近距离局部视图，只要不影响判断时都可以 ok=true，但 reason 要说明可用。\n")
                append("输出 JSON 格式：\n")
                append("{\"ok\":true|false,\"reason\":\"一句话原因\",\"issues\":[\"non_durian\"|\"wrong_angle\"|\"cropped\"|\"occluded\"|\"cluttered\"|\"blurry\"|\"dark\"|\"overexposed\"|\"glare\"|\"too_far\"|\"other\"]}\n")
                append("辅助指标：blurScore=").append(blurScore).append(", meanLuma=").append(meanLuma).append(", stdLuma=").append(stdLuma).append('\n')
                if (reasons.isNotEmpty()) {
                    append("快速检查提示：").append(reasons.joinToString(separator = "、")).append('\n')
                }
            }
            val raw = llmRepository.getVisionCompletion(systemPrompt, userPrompt, imagePath)
            parseDoranPhotoCheck(raw)
        } catch (e: Exception) {
            DoranPhotoCheck(ok = false, reason = "Doran 检查失败：${e.localizedMessage ?: "未知错误"}", issues = listOf("other"))
        }
    }

    private fun parseDoranPhotoCheck(text: String): DoranPhotoCheck {
        val trimmed = text.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        val jsonText = if (start >= 0 && end > start) trimmed.substring(start, end + 1) else "{}"
        return try {
            val obj = JSONObject(jsonText)
            val ok = obj.optBoolean("ok", false)
            val reason = obj.optString("reason", if (ok) "可以用" else "建议重拍")
            val issuesArr = obj.optJSONArray("issues")
            val issues = buildList {
                if (issuesArr != null) {
                    for (i in 0 until issuesArr.length()) {
                        val v = issuesArr.optString(i)
                        if (!v.isNullOrBlank()) add(v)
                    }
                }
            }
            DoranPhotoCheck(ok = ok, reason = reason, issues = issues)
        } catch (_: Exception) {
            DoranPhotoCheck(ok = false, reason = "Doran 输出解析失败，建议重拍", issues = listOf("other"))
        }
    }

    private fun photoAngleRequirement(stepLabel: String): String {
        return when (stepLabel) {
            "上面" -> "从果肩/顶部看，榴莲主体应居中完整，能看到顶部轮廓和刺分布，不应只拍到侧面。"
            "下面" -> "从底部看，优先看到果脐、底部星形或底部房线区域。允许为了看清底部而近距离拍摄，只要底部关键区域清晰、主体明确、不被手或桌面严重遮挡即可。"
            "左侧" -> "从左侧看，榴莲左侧轮廓应完整，能看到侧向弧度和刺分布，不能裁掉头尾。"
            "右侧" -> "从右侧看，榴莲右侧轮廓应完整，能看到侧向弧度和刺分布，不能裁掉头尾。"
            "正面" -> "从正面看，榴莲主体应完整居中，能看到正向整体形态、房线和刺分布。"
            else -> "按当前提示角度拍摄，主体完整、居中、无遮挡、背景干净。"
        }
    }

    fun requestEditParams() {
        viewModelScope.launch {
            addBotMessage("好滴～你可以在下面的表单里直接修改参数。")
            upsertActiveForm()
            updateCurrentSessionInList()
        }
    }

    private fun setFormStatus(formId: String?, status: InputFormStatus) {
        if (formId.isNullOrBlank()) return
        _messages.update { list ->
            list.map { msg ->
                if (msg is ChatMessage.InputFormWidgetMessage && msg.id == formId) {
                    msg.copy(status = status)
                } else {
                    msg
                }
            }
        }
        updateCurrentSessionInList()
    }

    private fun upsertActiveForm(moveToBottom: Boolean = false) {
        val id = activeFormId ?: randomString().also { activeFormId = it }
        _messages.update { list ->
            val normalized = list.map { msg ->
                if (msg is ChatMessage.InputFormWidgetMessage && msg.mode == InputFormMode.Active && msg.id != id) {
                    msg.copy(mode = InputFormMode.History)
                } else {
                    msg
                }
            }
            val exists = normalized.any { it is ChatMessage.InputFormWidgetMessage && it.id == id }
            if (exists) {
                val updated = normalized.mapNotNull { msg ->
                    if (msg is ChatMessage.InputFormWidgetMessage && msg.id == id) {
                        if (moveToBottom) {
                            null
                        } else {
                            msg.copy(params = currentParams, mode = InputFormMode.Active, status = InputFormStatus.Pending)
                        }
                    } else {
                        msg
                    }
                }
                if (moveToBottom) {
                    updated + ChatMessage.InputFormWidgetMessage(
                        id = id,
                        params = currentParams,
                        mode = InputFormMode.Active,
                        status = InputFormStatus.Pending
                    )
                } else {
                    updated
                }
            } else {
                normalized + ChatMessage.InputFormWidgetMessage(
                    id = id,
                    params = currentParams,
                    mode = InputFormMode.Active,
                    status = InputFormStatus.Pending
                )
            }
        }
        updateCurrentSessionInList()
    }

    fun setVarietyFromQuick(variety: DurianVariety) {
        val before = currentParams
        if (before.variety == variety) return
        applyParamsUpdate(before.copy(variety = variety), source = TaskUpdateSource.QuickDock)
    }

    fun setWeightFromQuick(weightKg: Float) {
        val w = weightKg.coerceAtLeast(0f)
        applyParamsUpdate(currentParams.copy(weightKg = w), source = TaskUpdateSource.QuickDock)
    }

    fun setShapeFromQuick(shape: DurianShape) {
        applyParamsUpdate(currentParams.copy(shape = shape), source = TaskUpdateSource.QuickDock)
    }

    fun setLobesFromQuick(largeLobes: Int, smallLobes: Int) {
        applyParamsUpdate(
            currentParams.copy(
            largeLobes = largeLobes.coerceAtLeast(0),
            smallLobes = smallLobes.coerceAtLeast(0)
            ),
            source = TaskUpdateSource.QuickDock
        )
    }

    fun onParametersDraftChanged(params: DurianParameters) {
        applyParamsUpdate(params, source = TaskUpdateSource.ChatCard, announce = false)
    }

    fun onParametersSubmitted(messageId: String, params: DurianParameters) {
        if (currentTask.photos.status != PhotoSetStatus.Ready) {
            addBotMessage("硬性条件：还缺少拍摄图片（五个角度）。请先点击“立即拍摄”，完成后我才能开始分析。")
            val recent = _messages.value.takeLast(6)
            if (recent.none { it is ChatMessage.CameraWidgetMessage }) {
                addBotCameraWidget()
            }
            updateCurrentSessionInList()
            return
        }

        if (!params.isComplete()) {
            addBotMessage("我现在还需要一点点数据\uD83E\uDD7A，比如重量、房数和形态哦~")
            upsertActiveForm()
            return
        }

        prePhotoDrivenMessagesSnapshot = _messages.value.toList()
        prePhotoDrivenParamsSnapshot = params
        prePhotoDrivenTaskSnapshot = currentTask
        prePhotoDrivenActiveFormIdSnapshot = activeFormId
        cancelPhotoDrivenAnalysis()
        analyzingFormId = messageId
        lastAskedFormId = messageId
        applyParamsUpdate(
            params = params,
            shouldUpsertForm = false,
            source = TaskUpdateSource.ChatCard,
            forceParameterEvent = true
        )
        _messages.update { list ->
            list.map { msg ->
                if (msg is ChatMessage.InputFormWidgetMessage) {
                    when {
                        msg.id == messageId -> msg.copy(params = params, mode = InputFormMode.History, status = InputFormStatus.Analyzing)
                        msg.mode == InputFormMode.Active -> msg.copy(mode = InputFormMode.History)
                        else -> msg
                    }
                } else {
                    msg
                }
            }
        }
        activeFormId = null
        startPhotoDrivenAnalysisSimulation(formId = messageId, paramsSnapshot = params)
    }

    private fun applyParamsUpdate(
        params: DurianParameters,
        shouldUpsertForm: Boolean = true,
        source: TaskUpdateSource = TaskUpdateSource.AgentTool,
        announce: Boolean = true,
        forceParameterEvent: Boolean = false
    ) {
        applyTaskPatch(
            patch = DurianTaskPatch(
                params = params,
                analysis = if (params != currentParams) {
                    currentTask.analysis.copy(
                        status = AnalysisTaskStatus.Idle,
                        score = null,
                        level = null,
                        stage = null,
                        latestReport = null
                    )
                } else {
                    currentTask.analysis
                }
            ),
            source = source,
            shouldUpsertForm = shouldUpsertForm,
            announce = announce,
            forceParameterEvent = forceParameterEvent
        )
    }

    private fun startAnalysisRequestedByAgent() {
        if (_isReportAnalyzing.value) return

        if (currentTask.photos.status != PhotoSetStatus.Ready) {
            addBotMessage(
                when (currentTask.photos.status) {
                    PhotoSetStatus.Invalid -> "照片状态不适合分析，请先重新拍摄五个角度。"
                    PhotoSetStatus.Missing -> "开始分析前还缺五角度照片。请先拍摄，上面、下面、左侧、右侧、正面都要有。"
                    PhotoSetStatus.Ready -> ""
                }
            )
            val recent = _messages.value.takeLast(6)
            if (recent.none { it is ChatMessage.CameraWidgetMessage }) {
                addBotCameraWidget()
            }
            updateCurrentSessionInList()
            return
        }

        if (!currentParams.isComplete()) {
            addBotMessage("开始分析前还缺一些关键参数。你可以在下面的表单里补齐重量、房数和形态。")
            upsertActiveForm()
            updateCurrentSessionInList()
            return
        }

        val formId = activeFormId ?: randomString().also { generatedId ->
            activeFormId = generatedId
            _messages.update { list ->
                list + ChatMessage.InputFormWidgetMessage(
                    id = generatedId,
                    params = currentParams,
                    mode = InputFormMode.Active,
                    status = InputFormStatus.Pending
                )
            }
        }
        onParametersSubmitted(formId, currentParams)
    }

    fun requestAnalysisFromDock() {
        startAnalysisRequestedByAgent()
    }

    fun cancelPhotoDrivenAnalysis() {
        if (analysisProgressJob == null && analysisProgressMessageId.isNullOrBlank() && !_isReportAnalyzing.value) {
            return
        }
        analysisProgressJob?.cancel()
        analysisProgressJob = null
        _isReportAnalyzing.value = false
        _analysisStage.value = "已取消"
        applyTaskPatch(
            patch = DurianTaskPatch(
                analysis = currentTask.analysis.copy(
                    status = AnalysisTaskStatus.Cancelled,
                    stage = "已取消"
                )
            ),
            source = TaskUpdateSource.Analysis
        )
        _isThinking.value = false
        isAnalyzing = false

        val progressId = analysisProgressMessageId
        analysisProgressMessageId = null
        if (!progressId.isNullOrBlank()) {
            _messages.update { list ->
                list.map { msg ->
                    if (msg is ChatMessage.AnalysisProgressMessage && msg.id == progressId) {
                        val nextSteps = msg.steps.map { step ->
                            when (step.status) {
                                AnalysisStepStatus.Done -> step
                                AnalysisStepStatus.Failed -> step
                                else -> step.copy(status = AnalysisStepStatus.Cancelled)
                            }
                        }
                        msg.copy(
                            currentStepTitle = "已取消",
                            steps = nextSteps,
                            canCancel = false
                        )
                    } else {
                        msg
                    }
                }
            }
        }

        setFormStatus(analyzingFormId, InputFormStatus.Pending)
        analyzingFormId = null
        activeFormId = null
        upsertActiveForm()
        updateCurrentSessionInList()
    }

    fun resetToBeforePhotoDrivenAnalysis() {
        analysisProgressJob?.cancel()
        analysisProgressJob = null
        analysisProgressMessageId = null
        _isReportAnalyzing.value = false
        _analysisStage.value = "准备分析"
        applyTaskPatch(
            patch = DurianTaskPatch(
                analysis = AnalysisTaskSnapshot(status = AnalysisTaskStatus.Idle)
            ),
            source = TaskUpdateSource.Analysis
        )
        _isThinking.value = false
        isAnalyzing = false

        val snapshotMessages = prePhotoDrivenMessagesSnapshot
        val snapshotParams = prePhotoDrivenParamsSnapshot
        val snapshotTask = prePhotoDrivenTaskSnapshot
        val snapshotActiveFormId = prePhotoDrivenActiveFormIdSnapshot

        if (snapshotMessages != null && snapshotParams != null && snapshotTask != null) {
            _messages.value = snapshotMessages
            currentTask = snapshotTask.copy(params = snapshotParams)
            currentParams = currentTask.params
            _currentTaskState.value = currentTask
            _currentParamsFlow.value = currentParams
            _currentParamsState.value = currentParams
            activeFormId = snapshotActiveFormId
        } else {
            _messages.update { list ->
                list.filterNot { msg ->
                    msg is ChatMessage.ResultReportMessage || msg is ChatMessage.AnalysisProgressMessage
                }
            }
        }

        analyzingFormId = null
        upsertActiveForm()
        updateCurrentSessionInList()
    }

    private fun startPhotoDrivenAnalysisSimulation(formId: String, paramsSnapshot: DurianParameters) {
        analysisProgressJob?.cancel()
        val progressId = randomString()
        analysisProgressMessageId = progressId

        val baseSteps = listOf(
            AnalysisStep(key = "p0_quality", title = "P0 照片质量门禁"),
            AnalysisStep(key = "p1_segment", title = "P1 分割与归一化"),
            AnalysisStep(key = "p2_spike", title = "P2 刺特征（密度/方向/高度代理）"),
            AnalysisStep(key = "p3_shape", title = "P3 形态几何（体型/对称性/壳厚代理）"),
            AnalysisStep(key = "p4_priors", title = "P4 品种先验融合"),
            AnalysisStep(key = "p5_fusion", title = "P5 融合评分与解释")
        )

        val initialInterim = linkedMapOf(
            "输入参数" to buildString {
                val parts = mutableListOf<String>()
                paramsSnapshot.variety?.displayName?.let { parts.add("品种=$it") }
                paramsSnapshot.weightKg?.let { parts.add("重量=${it}kg") }
                parts.add("房数=${paramsSnapshot.largeLobes}/${paramsSnapshot.smallLobes}")
                paramsSnapshot.shape?.displayName?.let { parts.add("形态=$it") }
                append(parts.joinToString(separator = "，"))
            }
        )

        val progressMessage = ChatMessage.AnalysisProgressMessage(
            id = progressId,
            title = "Doran 动态视觉分析",
            overallProgress = 0f,
            currentStepTitle = baseSteps.firstOrNull()?.title,
            steps = baseSteps,
            interim = initialInterim,
            canCancel = true
        )

        _messages.update { list ->
            val idx = list.indexOfFirst { it.id == formId }
            if (idx < 0) {
                list + progressMessage
            } else {
                val out = list.toMutableList()
                out.add(idx + 1, progressMessage)
                out.toList()
            }
        }
        updateCurrentSessionInList()

        _isReportAnalyzing.value = true
        _analysisStage.value = baseSteps.firstOrNull()?.title ?: "分析中"
        applyTaskPatch(
            patch = DurianTaskPatch(
                analysis = AnalysisTaskSnapshot(
                    status = AnalysisTaskStatus.Running,
                    stage = _analysisStage.value
                )
            ),
            source = TaskUpdateSource.Analysis
        )
        setFormStatus(formId, InputFormStatus.Analyzing)

        analysisProgressJob = viewModelScope.launch {
            val interim = LinkedHashMap(initialInterim)
            var steps = baseSteps
            val traceStartedAt = mutableMapOf<String, Long>()
            val analysisTrace = mutableListOf<AnalysisTrace>()
            var finalReport: DoranAnalysisWorkflowEvent.FinalReport? = null

            suspend fun updateProgress(
                nextSteps: List<AnalysisStep>,
                currentTitle: String?,
                overall: Float,
                canCancel: Boolean
            ) {
                steps = nextSteps
                _messages.update { list ->
                    list.map { msg ->
                        if (msg is ChatMessage.AnalysisProgressMessage && msg.id == progressId) {
                            msg.copy(
                                steps = steps,
                                currentStepTitle = currentTitle,
                                overallProgress = overall.coerceIn(0f, 1f),
                                interim = interim.toMap(),
                                canCancel = canCancel
                            )
                        } else {
                            msg
                        }
                    }
                }
                updateCurrentSessionInList()
            }

            try {
                val photoCount = currentTask.photos.count
                DoranAdkAnalysisWorkflow()
                    .run(
                        sessionId = _currentSessionId.value ?: "analysis",
                        params = paramsSnapshot,
                        photoCount = photoCount,
                        qualityProfiles = currentTask.photos.qualityProfiles
                    )
                    .collect { event ->
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@collect
                        when (event) {
                            is DoranAnalysisWorkflowEvent.StepStarted -> {
                                traceStartedAt[event.step.key] = System.currentTimeMillis()
                                val index = baseSteps.indexOfFirst { it.key == event.step.key }.coerceAtLeast(0)
                                val runningSteps = baseSteps.mapIndexed { stepIndex, step ->
                                    when {
                                        stepIndex < index -> step.copy(status = AnalysisStepStatus.Done)
                                        stepIndex == index -> step.copy(status = AnalysisStepStatus.Running)
                                        else -> step.copy(status = AnalysisStepStatus.Pending)
                                    }
                                }
                                _analysisStage.value = event.step.title
                                applyTaskPatch(
                                    patch = DurianTaskPatch(
                                        analysis = currentTask.analysis.copy(
                                            status = AnalysisTaskStatus.Running,
                                            stage = event.step.title
                                        )
                                    ),
                                    source = TaskUpdateSource.Analysis
                                )
                                updateProgress(
                                    nextSteps = runningSteps,
                                    currentTitle = event.step.title,
                                    overall = index.toFloat() / baseSteps.size.toFloat(),
                                    canCancel = true
                                )
                            }
                            is DoranAnalysisWorkflowEvent.StepCompleted -> {
                                interim.putAll(event.interim)
                                analysisTrace += AnalysisTrace(
                                    stepKey = event.step.key,
                                    toolName = event.step.toolName,
                                    title = event.step.title,
                                    output = event.interim,
                                    startedAt = traceStartedAt[event.step.key] ?: System.currentTimeMillis(),
                                    completedAt = System.currentTimeMillis()
                                )
                                val index = baseSteps.indexOfFirst { it.key == event.step.key }.coerceAtLeast(0)
                                val doneSteps = baseSteps.mapIndexed { stepIndex, step ->
                                    when {
                                        stepIndex <= index -> step.copy(status = AnalysisStepStatus.Done)
                                        else -> step.copy(status = AnalysisStepStatus.Pending)
                                    }
                                }
                                updateProgress(
                                    nextSteps = doneSteps,
                                    currentTitle = if (index == baseSteps.lastIndex) null else baseSteps.getOrNull(index + 1)?.title,
                                    overall = (index + 1).toFloat() / baseSteps.size.toFloat(),
                                    canCancel = index != baseSteps.lastIndex
                                )
                            }
                            is DoranAnalysisWorkflowEvent.FinalReport -> {
                                finalReport = event
                                interim.putAll(event.interim)
                            }
                        }
                    }

                val result = finalReport ?: DoranAnalysisWorkflowEvent.FinalReport(
                    score = 75,
                    level = 3,
                    reportText = "ADK 工具链分析完成，但最终报告为空，已使用保底结果。",
                    interim = interim
                )
                _isReportAnalyzing.value = false
                analysisProgressJob = null
                analysisProgressMessageId = null
                _analysisStage.value = "完成"
                applyTaskPatch(
                    patch = DurianTaskPatch(
                        analysis = AnalysisTaskSnapshot(
                            status = AnalysisTaskStatus.Completed,
                            score = result.score,
                            level = result.level,
                            stage = "完成"
                        )
                    ),
                    source = TaskUpdateSource.Analysis
                )
                setFormStatus(formId, InputFormStatus.Done)
                analyzingFormId = null

                val imagePathsSnapshot =
                    (_messages.value.asReversed().firstOrNull { it is ChatMessage.ImageStripMessage } as? ChatMessage.ImageStripMessage)
                        ?.imagePaths
                        ?: emptyList()
                val analysisReport = AnalysisReport(
                    id = randomString(),
                    paramsSnapshot = paramsSnapshot,
                    imagePaths = imagePathsSnapshot,
                    score = result.score,
                    level = result.level,
                    reportText = result.reportText,
                    interim = result.interim,
                    trace = analysisTrace.toList(),
                    suggestions = buildReportActionSuggestions(
                        params = paramsSnapshot,
                        score = result.score,
                        level = result.level,
                        profiles = currentTask.photos.qualityProfiles,
                        interim = result.interim
                    ),
                    photoQualityProfiles = currentTask.photos.qualityProfiles
                )
                applyTaskPatch(
                    patch = DurianTaskPatch(
                        analysis = currentTask.analysis.copy(
                            status = AnalysisTaskStatus.Completed,
                            score = analysisReport.score,
                            level = analysisReport.level,
                            stage = "完成",
                            latestReport = analysisReport
                        )
                    ),
                    source = TaskUpdateSource.Analysis
                )
                val report = analysisReport.toResultReportMessage()
                _messages.update { it + report }
                userPreferencesRepository.recordReport(score = result.score, level = result.level, timestamp = report.timestamp)
                publishLatestReportWidgetContent(analysisReport)
                appendNewBadgeMessagesIfNeeded(analysisReport)
                DailyAdviceWidgetProvider.schedulePeriodicUpdates(getApplication())
                DailyAdviceWidgetProvider.updateAll(getApplication())
                BadgesWidgetProvider.updateAll(getApplication())
                Badge1WidgetProvider.updateAll(getApplication())
                Badge2WidgetProvider.updateAll(getApplication())
                Badge3WidgetProvider.updateAll(getApplication())
                Badge4WidgetProvider.updateAll(getApplication())
                Badge5WidgetProvider.updateAll(getApplication())
                updateCurrentSessionInList()
            } catch (e: Exception) {
                maybeAppendDevErrorLog(stage = "adk_analysis", errorText = e.localizedMessage ?: "ADK 分析流程失败")
                _isReportAnalyzing.value = false
                analysisProgressJob = null
                analysisProgressMessageId = null
                _analysisStage.value = "失败"
                applyTaskPatch(
                    patch = DurianTaskPatch(
                        analysis = currentTask.analysis.copy(
                            status = AnalysisTaskStatus.Failed,
                            stage = "失败"
                        )
                    ),
                    source = TaskUpdateSource.Analysis
                )
                setFormStatus(formId, InputFormStatus.Pending)
                analyzingFormId = null
                _messages.update { list ->
                    list.map { msg ->
                        if (msg is ChatMessage.AnalysisProgressMessage && msg.id == progressId) {
                            msg.copy(
                                steps = steps.map { step ->
                                    if (step.status == AnalysisStepStatus.Running) {
                                        step.copy(status = AnalysisStepStatus.Failed)
                                    } else {
                                        step
                                    }
                                },
                                currentStepTitle = "分析失败",
                                canCancel = false
                            )
                        } else {
                            msg
                        }
                    } + ChatMessage.TextMessage(
                        id = randomString(),
                        text = "ADK 分析流程遇到问题，请稍后重试。",
                        isFromUser = false
                    )
                }
                updateCurrentSessionInList()
            }
        }
    }

    private suspend fun checkIfReadyForReport() {
        if (isAnalyzing) return
        val photo = computePhotoRequirement(_messages.value)
        if (photo.state != PhotoRequirementState.Ready) {
            addBotMessage("硬性条件：还缺少拍摄图片（五个角度）。请先点击“立即拍摄”，完成后我才能开始分析。")
            val recent = _messages.value.takeLast(6)
            if (recent.none { it is ChatMessage.CameraWidgetMessage }) {
                addBotCameraWidget()
            }
            setFormStatus(analyzingFormId, InputFormStatus.Pending)
            return
        }

        if (!currentParams.isComplete()) {
            addBotMessage("我现在还需要一点点数据\uD83E\uDD7A，比如重量、房数和形态哦~")
            setFormStatus(analyzingFormId, InputFormStatus.Pending)
            upsertActiveForm()
            return
        }

        addBotMessage("收到全部数据啦！我开始进行多维度分析啦。")
        isAnalyzing = true
        _isThinking.value = true
        _isReportAnalyzing.value = true
        _analysisStage.value = analysisStages.first()
        setFormStatus(analyzingFormId, InputFormStatus.Analyzing)

        val paramsSnapshot = currentParams
        val imageResIdsSnapshot =
            (_messages.value.asReversed().firstOrNull { it is ChatMessage.ImageStripMessage } as? ChatMessage.ImageStripMessage)
                ?.imageResIds
                ?: emptyList()
        val imagePathsSnapshot =
            (_messages.value.asReversed().firstOrNull { it is ChatMessage.ImageStripMessage } as? ChatMessage.ImageStripMessage)
                ?.imagePaths
                ?: emptyList()
        
        val systemPrompt = """
            你是一个极具科技感与实用价值的“朵然 (Doran AI) 选果专家”。
            你正在通过“视觉主导+物理辅助”的融合算法评估一颗榴莲的出肉率。
            
            【评分标准】
            Level 1: 90~100分 (极高, >32%)
            Level 2: 80~89分 (高, 25%~32%)
            Level 3: 70~79分 (中, 20%~25%)
            Level 4: 60~69分 (低, 15%~20%)
            Level 5: <60分 (极低, <15%)
            
            【任务】
            根据用户的输入，给出一份简短、有温度、逻辑清晰的最终选果建议，并打分。
            
            【注意】
            - 回复必须严格按以下 JSON 格式返回，不可包含任何额外解释文本：
            {"score": 88, "level": 2, "reportText": "这里写你的评价"}
        """.trimIndent()

        val userPrompt = """
            当前榴莲的融合参数：
            - CV预测品种：金枕
            - 实际品种：${currentParams.variety?.displayName ?: "CV预测"}
            - CV预估体积：3200 cm³
            - CV预测壳厚：偏薄
            - 实际重量：${currentParams.weightKg} kg
            - 饱满大房数：${currentParams.largeLobes}
            - 干瘪小房数：${currentParams.smallLobes}
            - 整体形态：${currentParams.shape?.displayName}
            
            请基于以上数据，推断该榴莲的出肉率情况并给出最终建议。
        """.trimIndent()

        // Offload network call to IO dispatcher
        val llmResponseText = try {
            kotlinx.coroutines.coroutineScope {
                val stageJob = launch {
                    var index = 0
                    while (isAnalyzing) {
                        _analysisStage.value = analysisStages[index % analysisStages.size]
                        index++
                        delay(700)
                    }
                }
                val response = withContext(Dispatchers.IO) {
                    llmRepository.getChatCompletion(systemPrompt, userPrompt)
                }
                stageJob.cancel()
                response
            }
        } finally {
            _isThinking.value = false
            isAnalyzing = false
            _isReportAnalyzing.value = false
            _analysisStage.value = "完成"
        }

        if (llmResponseText.startsWith("Error:")) {
            maybeAppendDevErrorLog(stage = "report", errorText = llmResponseText)
            setFormStatus(analyzingFormId, InputFormStatus.Pending)
        }

        try {
            // Very naive JSON parsing since we are relying on LLM output
            val scoreMatch = Regex(""""score":\s*(\d+)""").find(llmResponseText)
            val levelMatch = Regex(""""level":\s*(\d+)""").find(llmResponseText)
            val textMatch = Regex(""""reportText":\s*"([^"]+)"""").find(llmResponseText)

            val score = scoreMatch?.groupValues?.get(1)?.toIntOrNull() ?: 75
            val level = levelMatch?.groupValues?.get(1)?.toIntOrNull() ?: 3
            val reportText = textMatch?.groupValues?.get(1) ?: llmResponseText // fallback to raw text

            val analysisReport = AnalysisReport(
                id = randomString(),
                paramsSnapshot = paramsSnapshot,
                imagePaths = imagePathsSnapshot,
                score = score,
                level = level,
                reportText = reportText.replace("\\n", "\n"),
                suggestions = buildReportActionSuggestions(
                    params = paramsSnapshot,
                    score = score,
                    level = level,
                    profiles = currentTask.photos.qualityProfiles,
                    interim = emptyMap()
                ),
                photoQualityProfiles = currentTask.photos.qualityProfiles
            )
            applyTaskPatch(
                patch = DurianTaskPatch(
                    analysis = AnalysisTaskSnapshot(
                        status = AnalysisTaskStatus.Completed,
                        score = analysisReport.score,
                        level = analysisReport.level,
                        stage = "完成",
                        latestReport = analysisReport
                    )
                ),
                source = TaskUpdateSource.Analysis
            )
            val report = analysisReport.toResultReportMessage(imageResIds = imageResIdsSnapshot)
            _messages.update { it + report }
            userPreferencesRepository.recordReport(score = score, level = level, timestamp = report.timestamp)
            publishLatestReportWidgetContent(analysisReport)
            appendNewBadgeMessagesIfNeeded(analysisReport)
            DailyAdviceWidgetProvider.schedulePeriodicUpdates(getApplication())
            DailyAdviceWidgetProvider.updateAll(getApplication())
            BadgesWidgetProvider.updateAll(getApplication())
            Badge1WidgetProvider.updateAll(getApplication())
            Badge2WidgetProvider.updateAll(getApplication())
            Badge3WidgetProvider.updateAll(getApplication())
            Badge4WidgetProvider.updateAll(getApplication())
            Badge5WidgetProvider.updateAll(getApplication())
            setFormStatus(analyzingFormId, InputFormStatus.Done)

        } catch (e: Exception) {
            maybeAppendDevErrorLog(
                stage = "report_parse",
                errorText = "${e.localizedMessage}\n${llmResponseText.take(800)}"
            )
            // Fallback
            val analysisReport = AnalysisReport(
                id = randomString(),
                paramsSnapshot = paramsSnapshot,
                imagePaths = imagePathsSnapshot,
                score = 0,
                level = 0,
                reportText = "解析大模型返回出错，原始返回：\n$llmResponseText",
                suggestions = listOf(
                    ReportActionSuggestion(
                        priority = 0,
                        category = "system",
                        title = "报告解析失败",
                        detail = "建议重新分析；如果多次失败，请切换严格工具模式或检查模型健康状态。",
                        actionLabel = "重新分析"
                    )
                ),
                photoQualityProfiles = currentTask.photos.qualityProfiles
            )
            applyTaskPatch(
                patch = DurianTaskPatch(
                    analysis = AnalysisTaskSnapshot(
                        status = AnalysisTaskStatus.Failed,
                        score = analysisReport.score,
                        level = analysisReport.level,
                        stage = "报告解析失败",
                        latestReport = analysisReport
                    )
                ),
                source = TaskUpdateSource.Analysis
            )
            val report = analysisReport.toResultReportMessage(imageResIds = imageResIdsSnapshot)
            _messages.update { it + report }
            userPreferencesRepository.recordReport(score = 0, level = 0, timestamp = report.timestamp)
            DailyAdviceWidgetProvider.schedulePeriodicUpdates(getApplication())
            DailyAdviceWidgetProvider.updateAll(getApplication())
            BadgesWidgetProvider.updateAll(getApplication())
            Badge1WidgetProvider.updateAll(getApplication())
            Badge2WidgetProvider.updateAll(getApplication())
            Badge3WidgetProvider.updateAll(getApplication())
            Badge4WidgetProvider.updateAll(getApplication())
            Badge5WidgetProvider.updateAll(getApplication())
            setFormStatus(analyzingFormId, InputFormStatus.Done)
        }
    }

    private suspend fun addInitialBotSequence() {
        addBotMessage(initialGreetingText)
        val activePath = llmRepository.getActiveModelPath()
        if (_isEngineReady.value) {
            val devModeEnabled = userPreferencesRepository.devMode.first()
            addBotMessage(
                buildString {
                    append("本机模型已就绪 ✅")
                    if (devModeEnabled && !activePath.isNullOrBlank()) {
                        append("\n").append(activePath)
                    }
                    append("\n\n你可以直接告诉我参数，或者发照片开始挑榴莲。")
                }
            )
        } else {
            addBotMessage(
                "本机模型未准备好。\n\n你可以去【模型管理】导入模型（本地选择 / URL 下载）。"
            )
            _messages.update {
                it + ChatMessage.ActionMessage(
                    id = randomString(),
                    actionType = "OPEN_MODEL_MANAGER",
                    label = "去模型管理"
                )
            }
        }
        delay(1000)
        addBotCameraWidget()
        updateCurrentSessionInList()
    }

    private fun addBotMessage(text: String) {
        _messages.update { 
            it + ChatMessage.TextMessage(id = randomString(), text = text, isFromUser = false) 
        }
    }

    private fun appendUiCard(action: DoranAdkAction.ShowUiCard) {
        _messages.update {
            it + ChatMessage.UiCardMessage(
                id = randomString(),
                cardType = action.cardType,
                title = action.title,
                body = action.body,
                bullets = action.bullets
            )
        }
    }

    private suspend fun appendToolCallMessages(actions: List<DoranAdkAction>) {
        if (actions.isEmpty()) return
        val enabled = userPreferencesRepository.devMode.first()
        if (!enabled) return
        val messages = actions.map { action ->
            when (action) {
                is DoranAdkAction.UpdateParameters -> ChatMessage.ToolCallMessage(
                    id = randomString(),
                    toolName = "update_durian_parameters",
                    argsSummary = action.params.toReadableSummary()
                )
                is DoranAdkAction.RequestInputForm -> ChatMessage.ToolCallMessage(
                    id = randomString(),
                    toolName = "request_input_form",
                    argsSummary = action.reason ?: "展示参数表单"
                )
                is DoranAdkAction.RequestCameraCapture -> ChatMessage.ToolCallMessage(
                    id = randomString(),
                    toolName = "request_camera_capture",
                    argsSummary = action.reason ?: "进入视觉识别与拍摄采集"
                )
                is DoranAdkAction.StartAnalysis -> ChatMessage.ToolCallMessage(
                    id = randomString(),
                    toolName = "start_durian_analysis",
                    argsSummary = action.reason ?: "检查参数与照片并开始分析"
                )
                is DoranAdkAction.Navigate -> ChatMessage.ToolCallMessage(
                    id = randomString(),
                    toolName = "navigate_app_screen",
                    argsSummary = buildString {
                        append(action.target.name)
                        action.reason?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    }
                )
                is DoranAdkAction.ShowUiCard -> ChatMessage.ToolCallMessage(
                    id = randomString(),
                    toolName = "show_ui_card",
                    argsSummary = "${action.cardType} · ${action.title}"
                )
                is DoranAdkAction.RestartSelection -> ChatMessage.ToolCallMessage(
                    id = randomString(),
                    toolName = "restart_selection",
                    argsSummary = "重置当前榴莲流程"
                )
            }
        }
        _messages.update { it + messages }
    }

    private fun emitNavigationEvent(target: DoranNavigationTarget) {
        when (target) {
            DoranNavigationTarget.HISTORY -> _navigationEvents.tryEmit(AgentChatNavigationEvent.OpenHistory)
            DoranNavigationTarget.MODEL_MANAGER -> _navigationEvents.tryEmit(AgentChatNavigationEvent.OpenModelManager)
            DoranNavigationTarget.SETTINGS -> _navigationEvents.tryEmit(AgentChatNavigationEvent.OpenSettings)
            DoranNavigationTarget.PROFILE -> _navigationEvents.tryEmit(AgentChatNavigationEvent.OpenProfile)
            DoranNavigationTarget.STATS -> _navigationEvents.tryEmit(AgentChatNavigationEvent.OpenStats)
            DoranNavigationTarget.WIDGETS -> _navigationEvents.tryEmit(AgentChatNavigationEvent.OpenWidgets)
            DoranNavigationTarget.ABOUT -> _navigationEvents.tryEmit(AgentChatNavigationEvent.OpenAbout)
            DoranNavigationTarget.LATEST_REPORT -> {
                val reportSessionId = _currentSessionId.value
                    ?.takeIf { sessionId ->
                        _messages.value.any { msg -> msg is ChatMessage.ResultReportMessage } ||
                            _sessions.value.firstOrNull { it.id == sessionId }?.task?.analysis?.latestReport != null
                    }
                if (reportSessionId != null) {
                    _navigationEvents.tryEmit(AgentChatNavigationEvent.OpenReport(reportSessionId))
                } else {
                    addBotMessage("当前会话里还没有可打开的分析报告。")
                }
            }
        }
    }

    private suspend fun maybeAppendDevErrorLog(stage: String, errorText: String) {
        val enabled = userPreferencesRepository.devMode.first()
        if (!enabled) return

        val last = LlmCallLogger.logs.value.lastOrNull()
        val detail = buildString {
            append("stage=").append(stage)
            if (last != null) {
                append("\nkind=").append(last.kind)
                append(" ok=").append(last.success)
                append(" durationMs=").append(last.durationMs)
                if (!last.modelPath.isNullOrBlank()) append("\nmodel=").append(last.modelPath)
                if (last.temperature != null || last.topP != null || last.topK != null) {
                    append("\n")
                    append("temp=").append(last.temperature)
                    append(" topP=").append(last.topP)
                    append(" topK=").append(last.topK)
                }
                if (!last.error.isNullOrBlank()) {
                    append("\nerror=").append(last.error)
                }
                if (!last.responsePreview.isNullOrBlank()) {
                    append("\npreview=").append(last.responsePreview)
                }
            }
            append("\nraw=").append(errorText.take(600))
        }

        _messages.update {
            it + ChatMessage.DevLogMessage(
                id = randomString(),
                title = "模型调用错误日志",
                detail = detail,
                isError = true
            )
        }
    }

    private fun addBotCameraWidget() {
        _messages.update { 
            it + ChatMessage.CameraWidgetMessage(id = randomString()) 
        }
    }

    fun resetChat() {
        startGreeting()
    }

    private fun buildAdkHistorySummary(): String {
        return _messages.value.takeLast(8).joinToString(separator = "\n") { msg ->
            when (msg) {
                is ChatMessage.TextMessage -> {
                    val role = if (msg.isFromUser) "用户" else "朵然"
                    "$role: ${msg.text.take(160)}"
                }
                is ChatMessage.ImageStripMessage -> {
                    val count = if (msg.imagePaths.isNotEmpty()) msg.imagePaths.size else msg.imageResIds.size
                    "照片: ${msg.label ?: "图片"} $count 张"
                }
                is ChatMessage.AudioMessage -> "语音: ${msg.durationMs / 1000}s ${msg.prompt.orEmpty()}"
                is ChatMessage.InputFormWidgetMessage -> "表单: ${msg.params}"
                is ChatMessage.ResultReportMessage -> "报告: ${msg.score}分 Level ${msg.level}"
                is ChatMessage.BadgeUnlockedMessage -> "徽章: ${msg.title}"
                is ChatMessage.AnalysisProgressMessage -> "分析进度: ${msg.currentStepTitle ?: msg.title}"
                is ChatMessage.CameraWidgetMessage -> "相机组件: 等待拍摄五角度照片"
                is ChatMessage.ActionMessage -> "动作: ${msg.label}"
                is ChatMessage.ToolCallMessage -> "工具调用: ${msg.toolName} ${msg.argsSummary}"
                is ChatMessage.UiCardMessage -> "卡片: ${msg.title}"
                is ChatMessage.DevLogMessage -> "开发日志: ${msg.title}"
            }
        }
    }

    private fun buildAdkPhotoState(): String {
        val photos = currentTask.photos
        return when (photos.status) {
            PhotoSetStatus.Ready -> "READY，已有 ${photos.count} 张五角度照片，可进入分析"
            PhotoSetStatus.Missing -> "MISSING，当前 ${photos.count} 张，分析前需要 5 张固定角度照片"
            PhotoSetStatus.Invalid -> "INVALID，最近照片无效，需要重新拍摄：${photos.invalidReason.orEmpty()}"
        }
    }

    private fun AnalysisReport.toResultReportMessage(
        imageResIds: List<Int> = emptyList()
    ): ChatMessage.ResultReportMessage {
        return ChatMessage.ResultReportMessage(
            id = id,
            paramsSnapshot = paramsSnapshot,
            imageResIds = imageResIds,
            imagePaths = imagePaths,
            score = score,
            level = level,
            reportText = reportText,
            timestamp = createdAt
        )
    }

    private suspend fun publishLatestReportWidgetContent(report: AnalysisReport) {
        val suggestion = report.suggestions.firstOrNull()?.let { item ->
            item.actionLabel?.let { "${item.title}：${item.detail}" } ?: item.detail
        } ?: when {
            report.level >= 4 || report.score < 70 -> "谨慎购买，建议现场复核房线、重量和开果保障。"
            report.score >= 85 -> "可以优先考虑，现场确认香气、果柄和敲击声音。"
            else -> "可作为候选，建议继续和其他榴莲比较。"
        }
        userPreferencesRepository.setLatestReportWidgetContent(
            score = report.score,
            level = report.level,
            variety = report.paramsSnapshot.variety?.displayName ?: "未标注品种",
            suggestion = suggestion.take(64),
            updatedAt = report.createdAt
        )
    }

    private suspend fun appendNewBadgeMessagesIfNeeded(report: AnalysisReport) {
        val alreadyNotified = userPreferencesRepository.badgeNotifiedIds.first()
        val unlocked = evaluateUnlockedBadges(report).filterNot { it.id in alreadyNotified }
        if (unlocked.isEmpty()) return
        unlocked.forEach { badge ->
            _messages.update {
                it + ChatMessage.BadgeUnlockedMessage(
                    id = randomString(),
                    badgeId = badge.id,
                    title = badge.title,
                    description = badge.description
                )
            }
            userPreferencesRepository.markBadgeNotified(badge.id)
        }
        updateCurrentSessionInList()
    }

    private fun buildReportActionSuggestions(
        params: DurianParameters,
        score: Int,
        level: Int,
        profiles: List<PhotoQualityProfile>,
        interim: Map<String, String>
    ): List<ReportActionSuggestion> {
        val profile = DurianVarietyProfiles.forVariety(params.variety)
        val suggestions = mutableListOf<ReportActionSuggestion>()
        val riskyProfiles = profiles.filter { !it.ok || it.forcedUse }
        val angleIssues = profiles
            .filter { photo -> photo.issues.any { it in setOf("wrong_angle", "cropped", "occluded", "cluttered", "too_far") } }
            .map { it.angleLabel.ifBlank { "某个角度" } }
            .distinct()

        if (angleIssues.isNotEmpty()) {
            suggestions += ReportActionSuggestion(
                priority = 0,
                category = "photo",
                title = "建议重拍关键角度",
                detail = "${angleIssues.joinToString("、")}存在角度、遮挡或主体完整性问题，会影响视觉判断。",
                actionLabel = "重拍照片"
            )
        } else if (riskyProfiles.isNotEmpty()) {
            suggestions += ReportActionSuggestion(
                priority = 1,
                category = "photo",
                title = "照片需要人工复核",
                detail = "有 ${riskyProfiles.size} 张照片是带风险使用，建议现场再次确认轮廓、房线和刺分布。",
                actionLabel = "复核照片"
            )
        }

        params.weightKg?.let { weight ->
            if (weight !in profile.typicalWeightKg) {
                suggestions += ReportActionSuggestion(
                    priority = 1,
                    category = "parameter",
                    title = "重量超出品种常见范围",
                    detail = "${profile.displayName} 常见重量为 ${profile.typicalWeightKg.display("kg")}，当前 ${weight}kg，建议复秤或核对品种。",
                    actionLabel = "复核重量"
                )
            } else if (weight !in profile.idealWeightKg) {
                suggestions += ReportActionSuggestion(
                    priority = 2,
                    category = "parameter",
                    title = "重量不在理想区间",
                    detail = "${profile.displayName} 理想重量为 ${profile.idealWeightKg.display("kg")}，当前仍可参考，但评分置信度略降。",
                    actionLabel = "现场复核"
                )
            }
        }

        if (params.smallLobes > profile.toleratedSmallLobes.last) {
            suggestions += ReportActionSuggestion(
                priority = 1,
                category = "yield",
                title = "小房偏多",
                detail = "${profile.displayName} 通常可容忍小房 ${profile.toleratedSmallLobes.first}~${profile.toleratedSmallLobes.last}，当前小房 ${params.smallLobes}，出肉率可能被拉低。",
                actionLabel = "谨慎购买"
            )
        }

        if (params.largeLobes > 0 && params.largeLobes !in profile.idealLargeLobes) {
            suggestions += ReportActionSuggestion(
                priority = 2,
                category = "yield",
                title = "大房数不在理想区间",
                detail = "${profile.displayName} 理想大房为 ${profile.idealLargeLobes.first}~${profile.idealLargeLobes.last}，当前 ${params.largeLobes}，建议结合房线饱满度判断。",
                actionLabel = "看房线"
            )
        }

        interim["风险提示"]?.takeIf { it.isNotBlank() && it != "无明显风险" }?.let { risk ->
            suggestions += ReportActionSuggestion(
                priority = 1,
                category = "risk",
                title = "标准库风险提示",
                detail = risk,
                actionLabel = "现场确认"
            )
        }

        suggestions += when {
            score <= 60 || level >= 5 -> ReportActionSuggestion(
                priority = 0,
                category = "decision",
                title = "不建议购买",
                detail = "综合评分偏低，除非价格明显有优势，否则建议换一颗。",
                actionLabel = "换一颗"
            )
            score < 70 || level >= 4 -> ReportActionSuggestion(
                priority = 1,
                category = "decision",
                title = "谨慎购买",
                detail = "存在低出肉率或照片/参数风险，建议和卖家确认可开果或降低预期。",
                actionLabel = "谨慎购买"
            )
            score >= 85 && riskyProfiles.isEmpty() -> ReportActionSuggestion(
                priority = 3,
                category = "decision",
                title = "可以优先考虑",
                detail = "照片质检和参数整体较稳，现场再确认香气、果柄和声音即可。",
                actionLabel = "可考虑"
            )
            else -> ReportActionSuggestion(
                priority = 2,
                category = "decision",
                title = "可作为候选",
                detail = "综合表现中等，建议现场再确认壳厚、香气和手感。",
                actionLabel = "继续比较"
            )
        }

        return suggestions
            .distinctBy { "${it.category}:${it.title}:${it.detail}" }
            .sortedBy { it.priority }
            .take(6)
    }

    private fun evaluateUnlockedBadges(report: AnalysisReport): List<UnlockedBadge> {
        val allSessions = _sessions.value.map {
            if (it.id == _currentSessionId.value) it.copy(messages = _messages.value, params = currentParams, task = currentTask)
            else it
        }
        val reports = allSessions.mapNotNull { session ->
            session.task.analysis.latestReport ?: (session.messages.lastOrNull { it is ChatMessage.ResultReportMessage } as? ChatMessage.ResultReportMessage)
                ?.let { legacy ->
                    AnalysisReport(
                        id = legacy.id,
                        paramsSnapshot = legacy.paramsSnapshot,
                        imagePaths = legacy.imagePaths,
                        score = legacy.score,
                        level = legacy.level,
                        reportText = legacy.reportText,
                        createdAt = legacy.timestamp
                    )
                }
        } + report
        val uniqueReports = reports.distinctBy { it.id }
        val profiles = allSessions.flatMap { it.task.photos.qualityProfiles } + report.photoQualityProfiles
        return buildList {
            if (profiles.count { it.ok && !it.forcedUse } >= 5) {
                add(UnlockedBadge("standard_photo", "徽章解锁：每日一鉴", "完成了一次标准五角度拍摄。"))
            }
            if (uniqueReports.any { it.level >= 4 }) {
                add(UnlockedBadge("low_yield_guard", "徽章解锁：排雷先锋", "识别到一颗低出肉率风险榴莲。"))
            }
            if (uniqueReports.count { it.level == 1 } >= 5) {
                add(UnlockedBadge("quality_catcher", "徽章解锁：品质捕手", "累计发现 5 颗 1 级品质榴莲。"))
            }
            val varietyCount = allSessions.mapNotNull { it.task.params.variety }
                .filter { it.name != "AUTO" && it.name != "OTHER" }
                .toSet()
                .size
            if (varietyCount >= 3 || uniqueReports.size >= 10) {
                add(UnlockedBadge("atlas_explorer", "徽章解锁：图鉴达人", "你已经覆盖了多个品种或完成了 10 次评测。"))
            }
            if (profiles.any { "wrong_angle" in it.issues } || uniqueReports.size >= 20) {
                add(UnlockedBadge("angle_watcher", "徽章解锁：狂热果粉", "你已经开始识别取图角度问题。"))
            }
        }
    }

    private data class UnlockedBadge(
        val id: String,
        val title: String,
        val description: String
    )

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun randomString() = UUID.randomUUID().toString()
}

private fun com.winter.durianai.domain.model.DurianParameters.toReadableSummary(): String {
    val parts = mutableListOf<String>()
    weightKg?.let { parts += "重量 ${it}kg" }
    if (largeLobes > 0 || smallLobes > 0) parts += "房数 ${largeLobes}/${smallLobes}"
    shape?.let { parts += "形态 ${it.displayName}" }
    variety?.let { parts += "品种 ${it.displayName}" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("，") ?: "暂未填写"
}


enum class PhotoRequirementState {
    Ready,
    Missing,
    Invalid
}

data class PhotoRequirement(
    val state: PhotoRequirementState,
    val count: Int
)

fun computePhotoRequirement(messages: List<ChatMessage>): PhotoRequirement {
    val lastImages = messages.asReversed().firstOrNull { it is ChatMessage.ImageStripMessage } as? ChatMessage.ImageStripMessage
        ?: return PhotoRequirement(state = PhotoRequirementState.Missing, count = 0)
    if (lastImages.label == "图片无效") {
        val count = if (lastImages.imagePaths.isNotEmpty()) lastImages.imagePaths.size else lastImages.imageResIds.size
        return PhotoRequirement(state = PhotoRequirementState.Invalid, count = count)
    }
    val count = if (lastImages.imagePaths.isNotEmpty()) lastImages.imagePaths.size else lastImages.imageResIds.size
    return if (count >= 5) PhotoRequirement(state = PhotoRequirementState.Ready, count = count)
    else PhotoRequirement(state = PhotoRequirementState.Missing, count = count)
}
