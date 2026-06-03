package com.winter.durianai.ui.screens.agent.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ChatInputBar(
    onSend: (String) -> Unit,
    onSendAudio: (audioPath: String, prompt: String, durationMs: Long) -> Unit,
    onAddImage: (fromCamera: Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var audioRecord by remember { mutableStateOf<AudioRecord?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    var recordingStartedAt by remember { mutableStateOf(0L) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    val recordingFlag = remember { AtomicBoolean(false) }
    val isRecording = audioRecord != null

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (!enabled || isRecording) return
        val dir = File(context.cacheDir, "voice_inputs").apply { mkdirs() }
        val file = File(dir, "voice_${UUID.randomUUID()}.wav")
        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate / 2)
        try {
            val record = AudioRecord(
                AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("录音设备初始化失败")
            }
            recordingFlag.set(true)
            record.startRecording()
            audioRecord = record
            recordingFile = file
            recordingStartedAt = System.currentTimeMillis()
            recordingJob = scope.launch(Dispatchers.IO) {
                writePcmWav(record, file, sampleRate, minBuffer, recordingFlag)
            }
        } catch (e: Exception) {
            audioRecord?.release()
            audioRecord = null
            recordingFile = null
            recordingFlag.set(false)
            file.delete()
            Toast.makeText(context, "无法开始录音：${e.localizedMessage ?: "未知错误"}", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopRecordingAndSend() {
        val record = audioRecord ?: return
        val file = recordingFile
        val job = recordingJob
        val elapsed = System.currentTimeMillis() - recordingStartedAt
        audioRecord = null
        recordingFile = null
        recordingJob = null
        recordingStartedAt = 0L
        recordingFlag.set(false)
        runCatching { record.stop() }
        scope.launch {
            job?.join()
            record.release()
            if (file == null || !file.exists() || file.length() <= WavHeaderSize || elapsed < 500L) {
                file?.delete()
                Toast.makeText(context, "录音太短，请再试一次", Toast.LENGTH_SHORT).show()
                return@launch
            }
            ensureMinimumWavDuration(file, sampleRate = 16_000, minimumMs = 2_000L)
            val prompt = text.trim()
            text = ""
            onSendAudio(file.absolutePath, prompt, elapsed)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startRecording()
        } else {
            Toast.makeText(context, "需要麦克风权限才能发送语音", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recordingFlag.set(false)
            runCatching { audioRecord?.stop() }
            audioRecord?.release()
            recordingFile?.delete()
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            IconButton(onClick = { showAttachmentMenu = true }, enabled = enabled && !isRecording) {
                Icon(Icons.Default.AddCircleOutline, contentDescription = "Add Attachment", tint = Color.Gray)
            }
            DropdownMenu(
                expanded = showAttachmentMenu && enabled,
                onDismissRequest = { showAttachmentMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("拍照") },
                    onClick = {
                        showAttachmentMenu = false
                        onAddImage(true)
                    },
                    leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("从相册选择") },
                    onClick = {
                        showAttachmentMenu = false
                        onAddImage(false)
                    },
                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) }
                )
            }
        }

        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = {
                Text(
                    when {
                        !enabled -> "模型加载中…"
                        isRecording -> "正在录音，点停止发送…"
                        else -> "和朵然分享..."
                    }
                )
            },
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            maxLines = 4,
            readOnly = isRecording || !enabled,
            enabled = enabled
        )

        if (isRecording) {
            IconButton(
                onClick = { stopRecordingAndSend() },
                modifier = Modifier.background(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(50)
                ),
                enabled = enabled
            ) {
                Icon(
                    imageVector = Icons.Default.StopCircle,
                    contentDescription = "停止录音并发送",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        } else if (text.isBlank()) {
            IconButton(
                onClick = {
                    Toast.makeText(context, "当前版本暂不稳定，无法使用。", Toast.LENGTH_SHORT).show()
                },
                enabled = enabled
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "录音并发送给模型",
                    tint = Color.Gray
                )
            }
        } else {
            IconButton(
                onClick = {
                    onSend(text)
                    text = ""
                },
                modifier = Modifier.background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(50)
                ),
                enabled = enabled
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

private const val WavHeaderSize = 44

private fun writePcmWav(
    audioRecord: AudioRecord,
    file: File,
    sampleRate: Int,
    bufferSize: Int,
    recordingFlag: AtomicBoolean
) {
    RandomAccessFile(file, "rw").use { out ->
        out.setLength(0)
        out.write(ByteArray(WavHeaderSize))
        val buffer = ByteArray(bufferSize)
        var pcmBytes = 0L
        while (recordingFlag.get()) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0) {
                out.write(buffer, 0, read)
                pcmBytes += read.toLong()
            }
        }
        out.seek(0)
        out.write(wavHeader(pcmBytes, sampleRate))
    }
}

private fun wavHeader(pcmBytes: Long, sampleRate: Int): ByteArray {
    val channels = 1
    val bitsPerSample = 16
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val totalDataLen = pcmBytes + 36
    return ByteArray(WavHeaderSize).also { header ->
        fun putString(offset: Int, value: String) {
            value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        fun putIntLe(offset: Int, value: Long) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value shr 8) and 0xff).toByte()
            header[offset + 2] = ((value shr 16) and 0xff).toByte()
            header[offset + 3] = ((value shr 24) and 0xff).toByte()
        }
        fun putShortLe(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value shr 8) and 0xff).toByte()
        }
        putString(0, "RIFF")
        putIntLe(4, totalDataLen)
        putString(8, "WAVE")
        putString(12, "fmt ")
        putIntLe(16, 16)
        putShortLe(20, 1)
        putShortLe(22, channels)
        putIntLe(24, sampleRate.toLong())
        putIntLe(28, byteRate.toLong())
        putShortLe(32, blockAlign)
        putShortLe(34, bitsPerSample)
        putString(36, "data")
        putIntLe(40, pcmBytes)
    }
}

private fun ensureMinimumWavDuration(file: File, sampleRate: Int, minimumMs: Long) {
    val minPcmBytes = sampleRate * 2L * minimumMs / 1000L
    RandomAccessFile(file, "rw").use { wav ->
        val pcmBytes = (wav.length() - WavHeaderSize).coerceAtLeast(0L)
        if (pcmBytes >= minPcmBytes) return

        wav.seek(wav.length())
        wav.write(ByteArray((minPcmBytes - pcmBytes).toInt()))
        wav.seek(0)
        wav.write(wavHeader(minPcmBytes, sampleRate))
    }
}
