package com.winter.durianai.data.remote.llm

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max

internal fun shortModelName(modelPath: String?): String {
    if (modelPath.isNullOrBlank()) return "null"
    return File(modelPath).name
}

internal fun formatBytes(size: Long): String {
    if (size < 1024) return "${size}B"
    if (size < 1024 * 1024) return String.format("%.1fKB", size / 1024.0)
    return String.format("%.2fMB", size / (1024.0 * 1024.0))
}

internal fun readWavDurationMs(audioFile: File): Long? {
    if (!audioFile.exists() || audioFile.length() < 44L) return null
    return runCatching {
        RandomAccessFile(audioFile, "r").use { raf ->
            raf.seek(22)
            val channels = raf.readUnsignedShortLE()
            raf.seek(24)
            val sampleRate = raf.readIntLE()
            raf.seek(34)
            val bitsPerSample = raf.readUnsignedShortLE()
            raf.seek(40)
            val dataSize = raf.readIntLE().toLong() and 0xffffffffL
            val bytesPerSecond = sampleRate.toLong() * max(1, channels) * max(1, bitsPerSample) / 8L
            if (bytesPerSecond <= 0L) null else (dataSize * 1000L) / bytesPerSecond
        }
    }.getOrNull()
}

internal fun buildAudioDebugSummary(
    requestId: String,
    modelPath: String?,
    audioPath: String,
    audioFileSizeBytes: Long,
    audioDurationMs: Long?,
    stage: String,
    resultType: String,
    extra: String? = null
): String {
    return buildString {
        append("requestId=").append(requestId)
        append(" stage=").append(stage)
        append(" result=").append(resultType)
        append(" model=").append(shortModelName(modelPath))
        append(" modelPath=").append(modelPath ?: "null")
        append(" audioPath=").append(audioPath)
        append(" audioSize=").append(audioFileSizeBytes).append("B")
        append(" audioSizeHuman=").append(formatBytes(audioFileSizeBytes))
        append(" audioDurationMs=").append(audioDurationMs ?: -1L)
        if (!extra.isNullOrBlank()) {
            append(" extra=").append(extra.replace('\n', ' '))
        }
    }
}

private fun RandomAccessFile.readUnsignedShortLE(): Int {
    val b0 = read()
    val b1 = read()
    if (b0 < 0 || b1 < 0) return 0
    return (b1 shl 8) or b0
}

private fun RandomAccessFile.readIntLE(): Int {
    val b0 = read()
    val b1 = read()
    val b2 = read()
    val b3 = read()
    if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return 0
    return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
}
