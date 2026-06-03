package com.winter.durianai.data.remote.llm

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioLlmService : Service() {

    companion object {
        private const val Tag = "DoranAudioService"
        const val EXTRA_RECEIVER = "receiver"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_SYSTEM_PROMPT = "system_prompt"
        const val EXTRA_USER_PROMPT = "user_prompt"
        const val EXTRA_AUDIO_PATH = "audio_path"
        const val EXTRA_TEMPERATURE = "temperature"
        const val EXTRA_TOP_P = "top_p"
        const val EXTRA_TOP_K = "top_k"
        const val EXTRA_RESULT_TEXT = "result_text"
        const val EXTRA_RESULT_STAGE = "result_stage"
        const val EXTRA_RESULT_KIND = "result_kind"
        const val EXTRA_RESULT_DETAIL = "result_detail"
        const val CLIENT_TIMEOUT_MS = 45_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val receiver = intent?.resultReceiverExtra(EXTRA_RECEIVER)
        if (intent == null || receiver == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        scope.launch {
            val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty().ifBlank { "unknown" }
            val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH).orEmpty()
            val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH).orEmpty()
            val audioFile = File(audioPath)
            val audioFileSizeBytes = audioFile.takeIf { it.exists() }?.length() ?: -1L
            val audioDurationMs = readWavDurationMs(audioFile)
            Log.i(
                Tag,
                buildAudioDebugSummary(
                    requestId = requestId,
                    modelPath = modelPath,
                    audioPath = audioPath,
                    audioFileSizeBytes = audioFileSizeBytes,
                    audioDurationMs = audioDurationMs,
                    stage = "service_start",
                    resultType = "start"
                )
            )
            val text = runCatching {
                runAudioInference(
                    requestId = requestId,
                    modelPath = modelPath,
                    systemPrompt = intent.getStringExtra(EXTRA_SYSTEM_PROMPT).orEmpty(),
                    userPrompt = intent.getStringExtra(EXTRA_USER_PROMPT).orEmpty(),
                    audioPath = audioPath,
                    temperature = intent.getDoubleExtra(EXTRA_TEMPERATURE, 0.7),
                    topP = intent.getDoubleExtra(EXTRA_TOP_P, 0.95),
                    topK = intent.getIntExtra(EXTRA_TOP_K, 10)
                )
            }.getOrElse { e ->
                val detail = buildAudioDebugSummary(
                    requestId = requestId,
                    modelPath = modelPath,
                    audioPath = audioPath,
                    audioFileSizeBytes = audioFileSizeBytes,
                    audioDurationMs = audioDurationMs,
                    stage = "service_exception",
                    resultType = e::class.java.simpleName,
                    extra = e.localizedMessage ?: "unknown"
                )
                Log.e(Tag, detail, e)
                "Error connecting to isolated audio model: ${e.localizedMessage}\n$detail"
            }
            val resultKind = when {
                text.startsWith("Error: Audio model process timeout") -> "timeout"
                text.startsWith("Error:") -> "error"
                else -> "success"
            }

            receiver.send(
                0,
                Bundle().apply {
                    putString(EXTRA_RESULT_TEXT, text)
                    putString(EXTRA_RESULT_STAGE, "service_reply")
                    putString(EXTRA_RESULT_KIND, resultKind)
                    putString(
                        EXTRA_RESULT_DETAIL,
                        buildAudioDebugSummary(
                            requestId = requestId,
                            modelPath = modelPath,
                            audioPath = audioPath,
                            audioFileSizeBytes = audioFileSizeBytes,
                            audioDurationMs = audioDurationMs,
                            stage = "service_reply",
                            resultType = resultKind
                        )
                    )
                }
            )
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runAudioInference(
        requestId: String,
        modelPath: String,
        systemPrompt: String,
        userPrompt: String,
        audioPath: String,
        temperature: Double,
        topP: Double,
        topK: Int
    ): String = withContext(Dispatchers.Default) {
        val modelFile = File(modelPath)
        val audioFile = File(audioPath)
        val audioFileSizeBytes = audioFile.takeIf { it.exists() }?.length() ?: -1L
        val audioDurationMs = readWavDurationMs(audioFile)
        if (!modelFile.exists()) {
            return@withContext "Error: Local model file missing.\n请去【模型管理】导入模型（本地选择 / URL 下载）。"
        }
        if (!audioFile.exists() || audioFile.length() <= 44L) {
            return@withContext "Error: Audio file missing or empty"
        }
        Log.i(
            Tag,
            buildAudioDebugSummary(
                requestId = requestId,
                modelPath = modelPath,
                audioPath = audioPath,
                audioFileSizeBytes = audioFileSizeBytes,
                audioDurationMs = audioDurationMs,
                stage = "service_inference",
                resultType = "engine_init"
            )
        )

        val engineConfig = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.CPU(),
            visionBackend = Backend.CPU(),
            cacheDir = File(cacheDir, "audio_lm").apply { mkdirs() }.absolutePath
        )

        Engine(engineConfig).use { engine ->
            engine.initialize()
            Log.i(
                Tag,
                buildAudioDebugSummary(
                    requestId = requestId,
                    modelPath = modelPath,
                    audioPath = audioPath,
                    audioFileSizeBytes = audioFileSizeBytes,
                    audioDurationMs = audioDurationMs,
                    stage = "service_inference",
                    resultType = "engine_ready"
                )
            )
            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                samplerConfig = SamplerConfig(
                    topK = topK,
                    topP = topP,
                    temperature = temperature
                )
            )
            engine.createConversation(conversationConfig).use { conversation ->
                Log.i(
                    Tag,
                    buildAudioDebugSummary(
                        requestId = requestId,
                        modelPath = modelPath,
                        audioPath = audioPath,
                        audioFileSizeBytes = audioFileSizeBytes,
                        audioDurationMs = audioDurationMs,
                        stage = "service_inference",
                        resultType = "send_audio_message"
                    )
                )
                val response = conversation.sendMessage(
                    Contents.of(
                        Content.AudioFile(audioFile.absolutePath),
                        Content.Text(userPrompt)
                    )
                )
                response.contents.contents
                    .mapNotNull { it as? Content.Text }
                    .joinToString(separator = "\n") { it.text }
                    .ifBlank { "Error: Empty response" }
                    .also { text ->
                        val resultType = if (text.startsWith("Error:")) "empty_or_error" else "success"
                        Log.i(
                            Tag,
                            buildAudioDebugSummary(
                                requestId = requestId,
                                modelPath = modelPath,
                                audioPath = audioPath,
                                audioFileSizeBytes = audioFileSizeBytes,
                                audioDurationMs = audioDurationMs,
                                stage = "service_inference",
                                resultType = resultType,
                                extra = text.take(180)
                            )
                        )
                    }
            }
        }
    }
}

private fun Intent.resultReceiverExtra(name: String): ResultReceiver? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, ResultReceiver::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
}
