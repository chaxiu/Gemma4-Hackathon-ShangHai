package com.winter.durianai.data.remote.llm

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.winter.durianai.data.local.prefs.UserPreferencesRepository
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class LlmCallLog(
    val id: String,
    val timestampMs: Long,
    val kind: String,
    val modelPath: String?,
    val temperature: Double?,
    val topP: Double?,
    val topK: Int?,
    val inputChars: Int,
    val outputChars: Int?,
    val durationMs: Long,
    val success: Boolean,
    val error: String?,
    val responsePreview: String?
)

object LlmCallLogger {
    private const val MaxEntries = 200
    private val _logs = MutableStateFlow<List<LlmCallLog>>(emptyList())
    val logs: StateFlow<List<LlmCallLog>> = _logs.asStateFlow()

    fun append(log: LlmCallLog) {
        _logs.update { (it + log).takeLast(MaxEntries) }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}

data class GpuCapability(
    val openClRuntimeAvailable: Boolean,
    val openClSamplerPluginPresent: Boolean,
    val webGpuSamplerPluginPresent: Boolean
)

class LlmRepository(private val context: Context) {
    companion object {
        private const val Tag = "DoranLlm"

        @Volatile
        private var instance: LlmRepository? = null

        fun getInstance(context: Context): LlmRepository {
            return instance ?: synchronized(this) {
                instance ?: LlmRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private var engine: Engine? = null
    private var isInitialized = false
    private val prefs = UserPreferencesRepository(context)
    private var didForceCpuFallback = false
    private var lastInitPreferredBackend: String? = null
    private var lastInitBackendUsed: String? = null
    private var lastInitError: String? = null
    private var lastInitFallbackReason: String? = null
    private val engineMutex = Mutex()
    private val initMutex = Mutex()
    private var gpuExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cpuExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var pendingGpuFuture: Future<*>? = null
    @Volatile private var pendingCpuFuture: Future<*>? = null

    private val preferredModelFileName = "doran.litertlm"
    private var activeModelPath: String? = null
    private val logcatEnabled: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun appendLog(
        kind: String,
        success: Boolean,
        durationMs: Long,
        modelPath: String?,
        error: String? = null,
        responsePreview: String? = null
    ) {
        LlmCallLogger.append(
            LlmCallLog(
                id = UUID.randomUUID().toString(),
                timestampMs = System.currentTimeMillis(),
                kind = kind,
                modelPath = modelPath,
                temperature = null,
                topP = null,
                topK = null,
                inputChars = 0,
                outputChars = null,
                durationMs = durationMs,
                success = success,
                error = error,
                responsePreview = responsePreview
            )
        )
    }

    private fun logInitStep(logcatEnabled: Boolean, message: String, modelPath: String? = null, t: Throwable? = null) {
        appendLog(
            kind = "initialize_step",
            success = t == null,
            durationMs = 0,
            modelPath = modelPath,
            error = t?.localizedMessage,
            responsePreview = message
        )
        if (logcatEnabled) {
            if (t == null) Log.i(Tag, message) else Log.e(Tag, message, t)
        }
    }

    private fun isOpenClAvailable(): Boolean {
        return try {
            System.loadLibrary("OpenCL")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isPluginLibPresent(fileName: String): Boolean {
        return try {
            val dir = File(context.applicationInfo.nativeLibraryDir)
            File(dir, fileName).exists()
        } catch (_: Throwable) {
            false
        }
    }

    fun getGpuCapability(): GpuCapability {
        return GpuCapability(
            openClRuntimeAvailable = isOpenClAvailable(),
            openClSamplerPluginPresent = isPluginLibPresent("libLiteRtTopKOpenClSampler.so"),
            webGpuSamplerPluginPresent = isPluginLibPresent("libLiteRtTopKWebGpuSampler.so")
        )
    }

    fun getLastInitPreferredBackend(): String? = lastInitPreferredBackend
    fun getLastInitBackendUsed(): String? = lastInitBackendUsed
    fun getLastInitError(): String? = lastInitError
    fun getLastInitFallbackReason(): String? = lastInitFallbackReason

    private fun resolveModelFile(): File? {
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir == null) return null

        val externalModelsDir = File(externalDir, "models")

        val externalPreferred = File(externalModelsDir, preferredModelFileName)
        if (externalPreferred.exists()) return externalPreferred
        externalModelsDir.listFiles()?.firstOrNull { it.isFile && it.extension.lowercase() == "litertlm" }?.let { return it }

        val legacyExternalPreferred = File(externalDir, preferredModelFileName)
        if (legacyExternalPreferred.exists()) return legacyExternalPreferred
        externalDir.listFiles()?.firstOrNull { it.isFile && it.extension.lowercase() == "litertlm" }?.let { return it }

        return null
    }

    fun findModelFile(): File? = resolveModelFile()

    fun getActiveModelPath(): String? = activeModelPath

    fun expectedModelLocationsText(): String {
        val external = context.getExternalFilesDir(null)
        val modelsDir = external?.let { File(it, "models") }
        return modelsDir?.absolutePath ?: ""
    }

    /**
     * Initializes the LiteRT-LM Engine.
     * This is a heavy operation and MUST be called from a background thread.
     */
    suspend fun initializeEngine(): Boolean {
        return initializeEngineInternal(forceCpuOnly = false)
    }

    private suspend fun initializeEngineInternal(forceCpuOnly: Boolean): Boolean {
        return initMutex.withLock {
        if (isInitialized) return true
        val startMs = System.currentTimeMillis()
        var resolvedPath: String? = null
        var error: String? = null
        var cancelled = false
        if (logcatEnabled) {
            Log.i(Tag, "initialize: entered (forceCpuOnly=$forceCpuOnly)")
        }
        val devMode = prefs.devMode.first()
        val enableLogcat = logcatEnabled || devMode
        val preferredBackend = if (forceCpuOnly) "cpu" else prefs.llmBackend.first()
        val preferredModelPath = prefs.activeModelPath.first()
        lastInitPreferredBackend = preferredBackend
        lastInitBackendUsed = null
        lastInitError = null
        lastInitFallbackReason = null
        logInitStep(
            logcatEnabled = enableLogcat,
            message = "initialize: start (preferredBackend=$preferredBackend forceCpuOnly=$forceCpuOnly preferredModelPath=${preferredModelPath ?: "null"})"
        )

        if (!preferredModelPath.isNullOrBlank()) {
            val preferredFile = runCatching { File(preferredModelPath) }.getOrNull()
            val externalBase = context.getExternalFilesDir(null)
            val externalOk = externalBase != null && preferredModelPath.startsWith(externalBase.absolutePath)
            if (preferredFile == null || !preferredFile.exists() || !externalOk) {
                prefs.setActiveModelPath(null)
                logInitStep(
                    logcatEnabled = enableLogcat,
                    message = "initialize: preferred modelPath invalid, cleared (exists=${preferredFile?.exists()} externalOk=$externalOk)",
                    modelPath = preferredModelPath
                )
            }
        }

        withContext(Dispatchers.Default) {
            val preferredFile = preferredModelPath?.let { runCatching { File(it) }.getOrNull() }
            val file = if (preferredFile != null && preferredFile.exists()) preferredFile else resolveModelFile()
            if (file == null || !file.exists()) {
                engine?.close()
                engine = null
                isInitialized = false
                activeModelPath = null
                logInitStep(logcatEnabled = enableLogcat, message = "initialize: no model file found")
                return@withContext
            }

            resolvedPath = file.absolutePath
            try {
                val openClAvailable = isOpenClAvailable()
                logInitStep(
                    logcatEnabled = enableLogcat,
                    message = "initialize: model resolved (${file.name}, size=${file.length()} bytes), openClAvailable=$openClAvailable",
                    modelPath = resolvedPath
                )
                val backends: List<Pair<String, Backend>> = when (preferredBackend.lowercase()) {
                    "gpu" -> {
                        if (openClAvailable) {
                            listOf("gpu" to Backend.GPU(), "cpu" to Backend.CPU())
                        } else {
                            lastInitFallbackReason = "未检测到 OpenCL 运行时，无法启用 GPU，已回退 CPU"
                            listOf("cpu" to Backend.CPU())
                        }
                    }
                    else -> listOf("cpu" to Backend.CPU())
                }

                var lastError: Exception? = null
                for ((label, backend) in backends) {
                    try {
                        engine?.close()
                        engine = null
                        isInitialized = false
                        activeModelPath = null

                        val engineConfig = EngineConfig(
                            modelPath = file.absolutePath,
                            backend = backend,
                            visionBackend = if (label == "gpu") Backend.CPU() else backend,
                            cacheDir = context.cacheDir.path
                        )
                        logInitStep(logcatEnabled = enableLogcat, message = "initialize: try backend=$label (creating engine)", modelPath = resolvedPath)
                        val createdEngine = if (label == "gpu") {
                            runOnGpuThreadWithTimeout(timeoutMs = 35_000) {
                                Engine(engineConfig).apply { initialize() }
                            }
                        } else {
                            runOnCpuThreadWithTimeout(timeoutMs = 110_000) {
                                Engine(engineConfig).apply { initialize() }
                            }
                        }
                        engine = createdEngine
                        isInitialized = true
                        activeModelPath = file.absolutePath
                        lastInitBackendUsed = label
                        if (preferredBackend.lowercase() == "gpu" && label == "cpu" && lastInitFallbackReason == null) {
                            lastInitFallbackReason = "GPU 初始化失败，已回退 CPU"
                        }
                        logInitStep(logcatEnabled = enableLogcat, message = "initialize: success backend=$label", modelPath = resolvedPath)
                        lastError = null
                        break
                    } catch (inner: Exception) {
                        logInitStep(
                            logcatEnabled = enableLogcat,
                            message = "initialize: backend=$label failed (${inner::class.java.simpleName})",
                            modelPath = resolvedPath,
                            t = inner
                        )
                        lastError = inner
                    }
                }

                if (lastError != null && !isInitialized) {
                    throw lastError
                }
            } catch (e: CancellationException) {
                cancelled = true
                error = e.localizedMessage
                engine?.close()
                engine = null
                isInitialized = false
                activeModelPath = null
                logInitStep(logcatEnabled = enableLogcat, message = "initialize: cancelled", modelPath = resolvedPath, t = e)
            } catch (e: Exception) {
                error = e.localizedMessage
                engine?.close()
                engine = null
                isInitialized = false
                activeModelPath = null
                logInitStep(logcatEnabled = enableLogcat, message = "initialize: failed (${e::class.java.simpleName})", modelPath = resolvedPath, t = e)
            }
        }

        val ok = isInitialized && engine != null
        val durationMs = System.currentTimeMillis() - startMs
        lastInitError = error
        appendLog(
            kind = "initialize",
            success = ok,
            durationMs = durationMs,
            modelPath = resolvedPath,
            error = if (cancelled) "初始化取消/超时" else error,
            responsePreview = buildString {
                append("preferred=").append(lastInitPreferredBackend ?: "null")
                append(" used=").append(lastInitBackendUsed ?: "null")
                if (!lastInitFallbackReason.isNullOrBlank()) append(" fallback=").append(lastInitFallbackReason)
            }
        )
        if (enableLogcat) {
            if (ok) {
                Log.i(Tag, "initialize: done ok (durationMs=$durationMs backend=${lastInitBackendUsed ?: "null"})")
            } else {
                Log.e(Tag, "initialize: done ERR (durationMs=$durationMs error=${lastInitError ?: "null"})")
            }
        }
        return ok
        }
    }

    /**
     * Sends a prompt to the local LiteRT-LM model.
     * @param systemPrompt The system context/instructions
     * @param userPrompt The actual query or data
     * @return The text response from the model
     */
    suspend fun getChatCompletion(systemPrompt: String, userPrompt: String): String {
        val devMode = prefs.devMode.first()

        if (!isInitialized || engine == null) {
            val ok = initializeEngine()
            if (!ok) {
                return "Error: Local model file missing.\n请去【模型管理】导入模型（本地选择 / URL 下载）。"
            }
        }

        return engineMutex.withLock {
        withContext(Dispatchers.Default) {
            val startMs = System.currentTimeMillis()
            val temperature = prefs.llmTemperature.first()
            val topP = prefs.llmTopP.first()
            val topK = prefs.llmTopK.first()
            var attempt = 0
            while (attempt < 2) {
                attempt++
                try {
                    val conversationConfig = ConversationConfig(
                        systemInstruction = Contents.of(systemPrompt),
                        samplerConfig = SamplerConfig(
                            topK = topK,
                            topP = topP,
                            temperature = temperature
                        ),
                    )

                    engine?.createConversation(conversationConfig)?.use { conversation ->
                        val backendPref = prefs.llmBackend.first().lowercase()
                        val backendNow = if (didForceCpuFallback) "cpu" else backendPref
                        val responseMessage = if (backendNow == "gpu") {
                            runOnGpuThreadWithTimeout(timeoutMs = 25_000) {
                                conversation.sendMessage(Contents.of(userPrompt))
                            }
                        } else {
                            conversation.sendMessage(Contents.of(userPrompt))
                        }
                        val text =
                            (responseMessage.contents.contents.firstOrNull() as? com.google.ai.edge.litertlm.Content.Text)?.text
                                ?: "Error: Empty response"
                        val durationMs = System.currentTimeMillis() - startMs
                        LlmCallLogger.append(
                            LlmCallLog(
                                id = UUID.randomUUID().toString(),
                                timestampMs = System.currentTimeMillis(),
                                kind = "chat",
                                modelPath = activeModelPath,
                                temperature = temperature,
                                topP = topP,
                                topK = topK,
                                inputChars = (systemPrompt.length + userPrompt.length),
                                outputChars = text.length,
                                durationMs = durationMs,
                                success = !text.startsWith("Error:"),
                                error = if (text.startsWith("Error:")) text else null,
                                responsePreview = text.take(240)
                            )
                        )
                        return@withContext text
                    } ?: return@withContext "Error: Engine is null"
                } catch (e: Exception) {
                    val raw = e.localizedMessage.orEmpty()
                    val looksLikeOpenCl = raw.contains("OpenCL", ignoreCase = true) ||
                        raw.contains("Could not load shared library", ignoreCase = true)
                    val looksLikeGpuHang = e is TimeoutException

                    if ((looksLikeOpenCl || looksLikeGpuHang) && !didForceCpuFallback) {
                        didForceCpuFallback = true
                        engine?.close()
                        engine = null
                        isInitialized = false
                        activeModelPath = null

                        val ok = initializeEngineInternal(forceCpuOnly = true)
                        if (ok) {
                            continue
                        }
                    }

                    if (devMode) {
                        e.printStackTrace()
                    }
                    val durationMs = System.currentTimeMillis() - startMs
                    val msg = "Error connecting to local Doran model: ${e.localizedMessage}"
                    LlmCallLogger.append(
                        LlmCallLog(
                            id = UUID.randomUUID().toString(),
                            timestampMs = System.currentTimeMillis(),
                            kind = "chat",
                            modelPath = activeModelPath,
                            temperature = temperature,
                            topP = topP,
                            topK = topK,
                            inputChars = (systemPrompt.length + userPrompt.length),
                            outputChars = null,
                            durationMs = durationMs,
                            success = false,
                            error = msg,
                            responsePreview = null
                        )
                    )
                    return@withContext msg
                }
            }

            "Error: Unknown"
        }
        }
    }

    suspend fun getVisionCompletion(
        systemPrompt: String,
        userPrompt: String,
        imagePath: String
    ): String {
        val devMode = prefs.devMode.first()

        if (!isInitialized || engine == null) {
            val ok = initializeEngine()
            if (!ok) {
                return "Error: Local model file missing.\n请去【模型管理】导入模型（本地选择 / URL 下载）。"
            }
        }

        return engineMutex.withLock {
            withContext(Dispatchers.Default) {
                val startMs = System.currentTimeMillis()
                val temperature = prefs.llmTemperature.first()
                val topP = prefs.llmTopP.first()
                val topK = prefs.llmTopK.first()
                var attempt = 0
                while (attempt < 2) {
                    attempt++
                    try {
                        val conversationConfig = ConversationConfig(
                            systemInstruction = Contents.of(systemPrompt),
                            samplerConfig = SamplerConfig(
                                topK = topK,
                                topP = topP,
                                temperature = temperature
                            ),
                        )

                        engine?.createConversation(conversationConfig)?.use { conversation ->
                            val backendPref = prefs.llmBackend.first().lowercase()
                            val backendNow = if (didForceCpuFallback) "cpu" else backendPref
                            val contents = Contents.of(
                                Content.ImageFile(imagePath),
                                Content.Text(userPrompt)
                            )
                            val responseMessage = if (backendNow == "gpu") {
                                runOnGpuThreadWithTimeout(timeoutMs = 25_000) {
                                    conversation.sendMessage(contents)
                                }
                            } else {
                                conversation.sendMessage(contents)
                            }
                            val text = responseMessage.contents.contents
                                .mapNotNull { it as? Content.Text }
                                .joinToString(separator = "\n") { it.text }
                                .ifBlank { "Error: Empty response" }

                            val durationMs = System.currentTimeMillis() - startMs
                            LlmCallLogger.append(
                                LlmCallLog(
                                    id = UUID.randomUUID().toString(),
                                    timestampMs = System.currentTimeMillis(),
                                    kind = "vision",
                                    modelPath = activeModelPath,
                                    temperature = temperature,
                                    topP = topP,
                                    topK = topK,
                                    inputChars = (systemPrompt.length + userPrompt.length),
                                    outputChars = text.length,
                                    durationMs = durationMs,
                                    success = !text.startsWith("Error:"),
                                    error = if (text.startsWith("Error:")) text else null,
                                    responsePreview = text.take(240)
                                )
                            )
                            return@withContext text
                        } ?: return@withContext "Error: Engine is null"
                    } catch (e: Exception) {
                        val raw = e.localizedMessage.orEmpty()
                        val looksLikeOpenCl = raw.contains("OpenCL", ignoreCase = true) ||
                            raw.contains("Could not load shared library", ignoreCase = true)
                        val looksLikeGpuHang = e is TimeoutException

                        if ((looksLikeOpenCl || looksLikeGpuHang) && !didForceCpuFallback) {
                            didForceCpuFallback = true
                            engine?.close()
                            engine = null
                            isInitialized = false
                            activeModelPath = null

                            val ok = initializeEngineInternal(forceCpuOnly = true)
                            if (ok) {
                                continue
                            }
                        }

                        if (devMode) {
                            e.printStackTrace()
                        }
                        val durationMs = System.currentTimeMillis() - startMs
                        val msg = "Error connecting to local Doran model: ${e.localizedMessage}"
                        LlmCallLogger.append(
                            LlmCallLog(
                                id = UUID.randomUUID().toString(),
                                timestampMs = System.currentTimeMillis(),
                                kind = "vision",
                                modelPath = activeModelPath,
                                temperature = temperature,
                                topP = topP,
                                topK = topK,
                                inputChars = (systemPrompt.length + userPrompt.length),
                                outputChars = null,
                                durationMs = durationMs,
                                success = false,
                                error = msg,
                                responsePreview = null
                            )
                        )
                        return@withContext msg
                    }
                }

                "Error: Unknown"
            }
        }
    }

    suspend fun getAudioCompletion(
        systemPrompt: String,
        userPrompt: String,
        audioPath: String
    ): String {
        val devMode = prefs.devMode.first()

        if (!isInitialized || engine == null) {
            val ok = initializeEngine()
            if (!ok) {
                return "Error: Local model file missing.\n请去【模型管理】导入模型（本地选择 / URL 下载）。"
            }
        }

        return engineMutex.withLock {
            withContext(Dispatchers.Default) {
                val startMs = System.currentTimeMillis()
                val temperature = prefs.llmTemperature.first()
                val topP = prefs.llmTopP.first()
                val topK = prefs.llmTopK.first()
                var attempt = 0
                while (attempt < 2) {
                    attempt++
                    try {
                        val conversationConfig = ConversationConfig(
                            systemInstruction = Contents.of(systemPrompt),
                            samplerConfig = SamplerConfig(
                                topK = topK,
                                topP = topP,
                                temperature = temperature
                            ),
                        )

                        engine?.createConversation(conversationConfig)?.use { conversation ->
                            val backendPref = prefs.llmBackend.first().lowercase()
                            val backendNow = if (didForceCpuFallback) "cpu" else backendPref
                            val contents = Contents.of(
                                Content.AudioFile(audioPath),
                                Content.Text(userPrompt)
                            )
                            val responseMessage = if (backendNow == "gpu") {
                                runOnGpuThreadWithTimeout(timeoutMs = 45_000) {
                                    conversation.sendMessage(contents)
                                }
                            } else {
                                conversation.sendMessage(contents)
                            }
                            val text = responseMessage.contents.contents
                                .mapNotNull { it as? Content.Text }
                                .joinToString(separator = "\n") { it.text }
                                .ifBlank { "Error: Empty response" }

                            val durationMs = System.currentTimeMillis() - startMs
                            LlmCallLogger.append(
                                LlmCallLog(
                                    id = UUID.randomUUID().toString(),
                                    timestampMs = System.currentTimeMillis(),
                                    kind = "audio",
                                    modelPath = activeModelPath,
                                    temperature = temperature,
                                    topP = topP,
                                    topK = topK,
                                    inputChars = (systemPrompt.length + userPrompt.length),
                                    outputChars = text.length,
                                    durationMs = durationMs,
                                    success = !text.startsWith("Error:"),
                                    error = if (text.startsWith("Error:")) text else null,
                                    responsePreview = text.take(240)
                                )
                            )
                            return@withContext text
                        } ?: return@withContext "Error: Engine is null"
                    } catch (e: Exception) {
                        val raw = e.localizedMessage.orEmpty()
                        val looksLikeOpenCl = raw.contains("OpenCL", ignoreCase = true) ||
                            raw.contains("Could not load shared library", ignoreCase = true)
                        val looksLikeGpuHang = e is TimeoutException

                        if ((looksLikeOpenCl || looksLikeGpuHang) && !didForceCpuFallback) {
                            didForceCpuFallback = true
                            engine?.close()
                            engine = null
                            isInitialized = false
                            activeModelPath = null

                            val ok = initializeEngineInternal(forceCpuOnly = true)
                            if (ok) {
                                continue
                            }
                        }

                        if (devMode) {
                            e.printStackTrace()
                        }
                        val durationMs = System.currentTimeMillis() - startMs
                        val msg = "Error connecting to local Doran model: ${e.localizedMessage}"
                        LlmCallLogger.append(
                            LlmCallLog(
                                id = UUID.randomUUID().toString(),
                                timestampMs = System.currentTimeMillis(),
                                kind = "audio",
                                modelPath = activeModelPath,
                                temperature = temperature,
                                topP = topP,
                                topK = topK,
                                inputChars = (systemPrompt.length + userPrompt.length),
                                outputChars = null,
                                durationMs = durationMs,
                                success = false,
                                error = msg,
                                responsePreview = null
                            )
                        )
                        return@withContext msg
                    }
                }

                "Error: Unknown"
            }
        }
    }

    private fun <T> runOnGpuThreadWithTimeout(timeoutMs: Long, block: () -> T): T {
        val future = gpuExecutor.submit<T> { block() }
        pendingGpuFuture = future
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            gpuExecutor.shutdownNow()
            gpuExecutor = Executors.newSingleThreadExecutor()
            if (logcatEnabled) Log.w(Tag, "GPU 操作超时 (timeoutMs=$timeoutMs)")
            throw TimeoutException("GPU 操作超时")
        } finally {
            if (pendingGpuFuture === future) pendingGpuFuture = null
        }
    }

    private fun <T> runOnCpuThreadWithTimeout(timeoutMs: Long, block: () -> T): T {
        val future = cpuExecutor.submit<T> { block() }
        pendingCpuFuture = future
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            cpuExecutor.shutdownNow()
            cpuExecutor = Executors.newSingleThreadExecutor()
            if (logcatEnabled) Log.w(Tag, "CPU 操作超时 (timeoutMs=$timeoutMs)")
            throw TimeoutException("CPU 操作超时")
        } finally {
            if (pendingCpuFuture === future) pendingCpuFuture = null
        }
    }

    fun close() {
        if (logcatEnabled) Log.i(Tag, "close: begin (activeModelPath=${activeModelPath ?: "null"})")
        pendingGpuFuture?.cancel(true)
        pendingCpuFuture?.cancel(true)
        pendingGpuFuture = null
        pendingCpuFuture = null

        gpuExecutor.shutdownNow()
        cpuExecutor.shutdownNow()
        gpuExecutor = Executors.newSingleThreadExecutor()
        cpuExecutor = Executors.newSingleThreadExecutor()

        val locked = engineMutex.tryLock()
        try {
            engine?.close()
            engine = null
            isInitialized = false
            activeModelPath = null
        } finally {
            if (locked) engineMutex.unlock()
        }
        appendLog(
            kind = "close",
            success = true,
            durationMs = 0,
            modelPath = null,
            responsePreview = "close called (locked=$locked)"
        )
        if (logcatEnabled) Log.i(Tag, "close: done (locked=$locked)")
    }
}
