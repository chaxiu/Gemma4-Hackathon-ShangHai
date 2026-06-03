package com.winter.durianai.data.remote.llm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import com.winter.durianai.data.local.prefs.UserPreferencesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

class AudioLlmClient(private val context: Context) {

    companion object {
        private const val Tag = "DoranAudioClient"
    }

    private val appContext = context.applicationContext
    private val prefs = UserPreferencesRepository(appContext)

    suspend fun getAudioCompletion(
        systemPrompt: String,
        userPrompt: String,
        audioPath: String
    ): String {
        val modelPath = prefs.activeModelPath.first()
            ?: LlmRepository.getInstance(appContext).findModelFile()?.absolutePath
            ?: return "Error: Local model file missing.\n请去【模型管理】导入模型（本地选择 / URL 下载）。"
        val requestId = UUID.randomUUID().toString()
        val audioFile = File(audioPath)
        val audioFileSizeBytes = audioFile.takeIf { it.exists() }?.length() ?: -1L
        val audioDurationMs = readWavDurationMs(audioFile)
        val requestSummary = buildAudioDebugSummary(
            requestId = requestId,
            modelPath = modelPath,
            audioPath = audioPath,
            audioFileSizeBytes = audioFileSizeBytes,
            audioDurationMs = audioDurationMs,
            stage = "client_request",
            resultType = "start"
        )
        Log.i(Tag, requestSummary)

        val result = CompletableDeferred<String>()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                val text = resultData?.getString(AudioLlmService.EXTRA_RESULT_TEXT)
                    ?: "Error: Audio model process returned empty result"
                val responseStage = resultData?.getString(AudioLlmService.EXTRA_RESULT_STAGE).orEmpty()
                val responseKind = resultData?.getString(AudioLlmService.EXTRA_RESULT_KIND).orEmpty()
                val responseDetail = resultData?.getString(AudioLlmService.EXTRA_RESULT_DETAIL).orEmpty()
                Log.i(
                    Tag,
                    buildAudioDebugSummary(
                        requestId = requestId,
                        modelPath = modelPath,
                        audioPath = audioPath,
                        audioFileSizeBytes = audioFileSizeBytes,
                        audioDurationMs = audioDurationMs,
                        stage = if (responseStage.isBlank()) "client_response" else responseStage,
                        resultType = if (responseKind.isBlank()) "reply" else responseKind,
                        extra = responseDetail.ifBlank { text.take(240) }
                    )
                )
                if (!result.isCompleted) {
                    result.complete(text)
                }
            }
        }

        val intent = Intent(appContext, AudioLlmService::class.java).apply {
            putExtra(AudioLlmService.EXTRA_RECEIVER, receiver)
            putExtra(AudioLlmService.EXTRA_REQUEST_ID, requestId)
            putExtra(AudioLlmService.EXTRA_MODEL_PATH, modelPath)
            putExtra(AudioLlmService.EXTRA_SYSTEM_PROMPT, systemPrompt)
            putExtra(AudioLlmService.EXTRA_USER_PROMPT, userPrompt)
            putExtra(AudioLlmService.EXTRA_AUDIO_PATH, audioPath)
            putExtra(AudioLlmService.EXTRA_TEMPERATURE, prefs.llmTemperature.first())
            putExtra(AudioLlmService.EXTRA_TOP_P, prefs.llmTopP.first())
            putExtra(AudioLlmService.EXTRA_TOP_K, prefs.llmTopK.first())
        }

        return try {
            appContext.startService(intent)
            withTimeoutOrNull(AudioLlmService.CLIENT_TIMEOUT_MS) { result.await() }
                ?: run {
                    val timeoutSummary = buildAudioDebugSummary(
                        requestId = requestId,
                        modelPath = modelPath,
                        audioPath = audioPath,
                        audioFileSizeBytes = audioFileSizeBytes,
                        audioDurationMs = audioDurationMs,
                        stage = "client_timeout",
                        resultType = "timeout_or_process_exit",
                        extra = "waitedMs=${AudioLlmService.CLIENT_TIMEOUT_MS}"
                    )
                    Log.e(Tag, timeoutSummary)
                    "Error: Audio model process timeout or crashed\n$timeoutSummary"
                }
        } catch (e: Exception) {
            val failureSummary = buildAudioDebugSummary(
                requestId = requestId,
                modelPath = modelPath,
                audioPath = audioPath,
                audioFileSizeBytes = audioFileSizeBytes,
                audioDurationMs = audioDurationMs,
                stage = "client_exception",
                resultType = e::class.java.simpleName,
                extra = e.localizedMessage ?: "unknown"
            )
            Log.e(Tag, failureSummary, e)
            "Error: Audio model process failed: ${e.localizedMessage}\n$failureSummary"
        }
    }
}
