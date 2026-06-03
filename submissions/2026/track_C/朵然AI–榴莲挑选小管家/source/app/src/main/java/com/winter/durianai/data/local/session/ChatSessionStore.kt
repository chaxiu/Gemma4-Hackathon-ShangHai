package com.winter.durianai.data.local.session

import android.content.Context
import com.winter.durianai.domain.model.AnalysisReport
import com.winter.durianai.domain.model.AnalysisTrace
import com.winter.durianai.domain.model.AnalysisTaskSnapshot
import com.winter.durianai.domain.model.AnalysisTaskStatus
import com.winter.durianai.domain.model.DurianParameters
import com.winter.durianai.domain.model.DurianShape
import com.winter.durianai.domain.model.DurianTaskState
import com.winter.durianai.domain.model.DurianVariety
import com.winter.durianai.domain.model.PhotoQualityProfile
import com.winter.durianai.domain.model.PhotoSet
import com.winter.durianai.domain.model.PhotoSetStatus
import com.winter.durianai.domain.model.ReportActionSuggestion
import com.winter.durianai.domain.model.TaskUpdateSource
import com.winter.durianai.ui.screens.agent.ChatSession
import com.winter.durianai.ui.screens.agent.models.AnalysisStep
import com.winter.durianai.ui.screens.agent.models.AnalysisStepStatus
import com.winter.durianai.ui.screens.agent.models.ChatMessage
import com.winter.durianai.ui.screens.agent.models.InputFormMode
import com.winter.durianai.ui.screens.agent.models.InputFormStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ChatSessionStore(context: Context) {
    private val file = File(context.filesDir, "doran_sessions.json")
    private val backupFile = File(context.filesDir, "doran_sessions.json.bak")

    fun loadSessions(): List<ChatSession> {
        return loadFrom(file).ifEmpty { loadFrom(backupFile) }
    }

    fun saveSessions(sessions: List<ChatSession>) {
        val root = JSONObject()
            .put("version", 1)
            .put("sessions", JSONArray().also { arr ->
                sessions.forEach { arr.put(it.toJson()) }
            })
        file.parentFile?.mkdirs()
        if (file.exists()) {
            runCatching { file.copyTo(backupFile, overwrite = true) }
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    private fun loadFrom(source: File): List<ChatSession> {
        if (!source.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(source.readText())
            val sessions = root.optJSONArray("sessions") ?: JSONArray()
            List(sessions.length()) { index -> sessions.getJSONObject(index).toChatSession() }
        }.getOrElse { emptyList() }
    }
}

private fun ChatSession.toJson(): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("title", title)
        .putOptString("avatarPath", avatarPath)
        .put("params", params.toJson())
        .put("task", task.toJson())
        .put("messages", JSONArray().also { arr -> messages.forEach { arr.put(it.toJson()) } })
}

private fun JSONObject.toChatSession(): ChatSession {
    val params = optJSONObject("params")?.toDurianParameters() ?: DurianParameters()
    return ChatSession(
        id = optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
        title = optString("title").ifBlank { "挑一个榴莲" },
        avatarPath = optNullableString("avatarPath"),
        params = params,
        task = optJSONObject("task")?.toDurianTaskState() ?: DurianTaskState(params = params),
        messages = optJSONArray("messages")?.toChatMessages().orEmpty()
    )
}

private fun DurianTaskState.toJson(): JSONObject {
    return JSONObject()
        .put("params", params.toJson())
        .put("photos", photos.toJson())
        .put("analysis", analysis.toJson())
        .put("lastUpdatedBy", lastUpdatedBy.name)
}

private fun JSONObject.toDurianTaskState(): DurianTaskState {
    return DurianTaskState(
        params = optJSONObject("params")?.toDurianParameters() ?: DurianParameters(),
        photos = optJSONObject("photos")?.toPhotoSet() ?: PhotoSet(),
        analysis = optJSONObject("analysis")?.toAnalysisTaskSnapshot() ?: AnalysisTaskSnapshot(),
        lastUpdatedBy = enumValueOrNull<TaskUpdateSource>(optString("lastUpdatedBy")) ?: TaskUpdateSource.System
    )
}

private fun DurianParameters.toJson(): JSONObject {
    return JSONObject()
        .putNullable("weightKg", weightKg)
        .put("largeLobes", largeLobes)
        .put("smallLobes", smallLobes)
        .putNullable("shape", shape?.name)
        .putNullable("variety", variety?.name)
}

private fun JSONObject.toDurianParameters(): DurianParameters {
    return DurianParameters(
        weightKg = if (isNull("weightKg")) null else optDouble("weightKg").toFloat(),
        largeLobes = optInt("largeLobes", 0),
        smallLobes = optInt("smallLobes", 0),
        shape = enumValueOrNull<DurianShape>(optString("shape")),
        variety = enumValueOrNull<DurianVariety>(optString("variety"))
    )
}

private fun PhotoSet.toJson(): JSONObject {
    return JSONObject()
        .put("imagePaths", imagePaths.toStringJsonArray())
        .put("status", status.name)
        .putNullable("invalidReason", invalidReason)
        .put("qualityProfiles", JSONArray().also { arr -> qualityProfiles.forEach { arr.put(it.toJson()) } })
}

private fun JSONObject.toPhotoSet(): PhotoSet {
    return PhotoSet(
        imagePaths = optJSONArray("imagePaths")?.toStringList().orEmpty(),
        status = enumValueOrNull<PhotoSetStatus>(optString("status")) ?: PhotoSetStatus.Missing,
        invalidReason = optNullableString("invalidReason"),
        qualityProfiles = optJSONArray("qualityProfiles")?.toPhotoQualityProfiles().orEmpty()
    )
}

private fun PhotoQualityProfile.toJson(): JSONObject {
    return JSONObject()
        .put("angleLabel", angleLabel)
        .put("imagePath", imagePath)
        .put("ok", ok)
        .put("reason", reason)
        .put("issues", issues.toStringJsonArray())
        .put("blurScore", blurScore)
        .put("meanLuma", meanLuma)
        .put("stdLuma", stdLuma)
        .put("forcedUse", forcedUse)
}

private fun JSONObject.toPhotoQualityProfile(): PhotoQualityProfile {
    return PhotoQualityProfile(
        angleLabel = optString("angleLabel"),
        imagePath = optString("imagePath"),
        ok = optBoolean("ok", false),
        reason = optString("reason"),
        issues = optJSONArray("issues")?.toStringList().orEmpty(),
        blurScore = optDouble("blurScore", 0.0),
        meanLuma = optDouble("meanLuma", 0.0),
        stdLuma = optDouble("stdLuma", 0.0),
        forcedUse = optBoolean("forcedUse", false)
    )
}

private fun AnalysisTaskSnapshot.toJson(): JSONObject {
    return JSONObject()
        .put("status", status.name)
        .putNullable("score", score)
        .putNullable("level", level)
        .putNullable("stage", stage)
        .putNullable("latestReport", latestReport?.toJson())
}

private fun JSONObject.toAnalysisTaskSnapshot(): AnalysisTaskSnapshot {
    return AnalysisTaskSnapshot(
        status = enumValueOrNull<AnalysisTaskStatus>(optString("status")) ?: AnalysisTaskStatus.Idle,
        score = if (isNull("score")) null else optInt("score"),
        level = if (isNull("level")) null else optInt("level"),
        stage = optNullableString("stage"),
        latestReport = optJSONObject("latestReport")?.toAnalysisReport()
    )
}

private fun AnalysisReport.toJson(): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("paramsSnapshot", paramsSnapshot.toJson())
        .put("imagePaths", imagePaths.toStringJsonArray())
        .put("score", score)
        .put("level", level)
        .put("reportText", reportText)
        .put("interim", interim.toJsonObject())
        .put("trace", JSONArray().also { arr -> trace.forEach { arr.put(it.toJson()) } })
        .put("suggestions", JSONArray().also { arr -> suggestions.forEach { arr.put(it.toJson()) } })
        .put("photoQualityProfiles", JSONArray().also { arr -> photoQualityProfiles.forEach { arr.put(it.toJson()) } })
        .put("createdAt", createdAt)
}

private fun JSONObject.toAnalysisReport(): AnalysisReport {
    return AnalysisReport(
        id = optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
        paramsSnapshot = optJSONObject("paramsSnapshot")?.toDurianParameters() ?: DurianParameters(),
        imagePaths = optJSONArray("imagePaths")?.toStringList().orEmpty(),
        score = optInt("score", 0),
        level = optInt("level", 0),
        reportText = optString("reportText"),
        interim = optJSONObject("interim")?.toStringMap().orEmpty(),
        trace = optJSONArray("trace")?.toAnalysisTraces().orEmpty(),
        suggestions = optJSONArray("suggestions")?.toReportActionSuggestions().orEmpty(),
        photoQualityProfiles = optJSONArray("photoQualityProfiles")?.toPhotoQualityProfiles().orEmpty(),
        createdAt = optLong("createdAt", System.currentTimeMillis())
    )
}

private fun AnalysisTrace.toJson(): JSONObject {
    return JSONObject()
        .put("stepKey", stepKey)
        .put("toolName", toolName)
        .put("title", title)
        .put("output", output.toJsonObject())
        .put("startedAt", startedAt)
        .put("completedAt", completedAt)
}

private fun JSONObject.toAnalysisTrace(): AnalysisTrace {
    return AnalysisTrace(
        stepKey = optString("stepKey"),
        toolName = optString("toolName"),
        title = optString("title"),
        output = optJSONObject("output")?.toStringMap().orEmpty(),
        startedAt = optLong("startedAt", System.currentTimeMillis()),
        completedAt = optLong("completedAt", System.currentTimeMillis())
    )
}

private fun ReportActionSuggestion.toJson(): JSONObject {
    return JSONObject()
        .put("priority", priority)
        .put("category", category)
        .put("title", title)
        .put("detail", detail)
        .putNullable("actionLabel", actionLabel)
}

private fun JSONObject.toReportActionSuggestion(): ReportActionSuggestion {
    return ReportActionSuggestion(
        priority = optInt("priority", 2),
        category = optString("category"),
        title = optString("title"),
        detail = optString("detail"),
        actionLabel = optNullableString("actionLabel")
    )
}

private fun ChatMessage.toJson(): JSONObject {
    val obj = JSONObject()
        .put("id", id)
        .put("timestamp", timestamp)
        .put("isFromUser", isFromUser)
    when (this) {
        is ChatMessage.TextMessage -> obj.put("type", "text").put("text", text)
        is ChatMessage.ImageStripMessage -> obj.put("type", "image_strip")
            .put("imageResIds", imageResIds.toIntJsonArray())
            .put("imagePaths", imagePaths.toStringJsonArray())
            .putNullable("label", label)
            .put("showDoranCheckAction", showDoranCheckAction)
        is ChatMessage.AudioMessage -> obj.put("type", "audio")
            .put("audioPath", audioPath)
            .put("durationMs", durationMs)
            .putNullable("prompt", prompt)
        is ChatMessage.CameraWidgetMessage -> obj.put("type", "camera_widget")
        is ChatMessage.InputFormWidgetMessage -> obj.put("type", "input_form")
            .put("params", params.toJson())
            .put("mode", mode.name)
            .put("status", status.name)
        is ChatMessage.ResultReportMessage -> obj.put("type", "result_report")
            .put("paramsSnapshot", paramsSnapshot.toJson())
            .put("imageResIds", imageResIds.toIntJsonArray())
            .put("imagePaths", imagePaths.toStringJsonArray())
            .put("score", score)
            .put("level", level)
            .put("reportText", reportText)
        is ChatMessage.BadgeUnlockedMessage -> obj.put("type", "badge_unlocked")
            .put("badgeId", badgeId)
            .put("title", title)
            .put("description", description)
        is ChatMessage.AnalysisProgressMessage -> obj.put("type", "analysis_progress")
            .put("title", title)
            .put("overallProgress", overallProgress.toDouble())
            .putNullable("currentStepTitle", currentStepTitle)
            .put("steps", JSONArray().also { arr -> steps.forEach { arr.put(it.toJson()) } })
            .put("interim", interim.toJsonObject())
            .put("canCancel", canCancel)
        is ChatMessage.ActionMessage -> obj.put("type", "action")
            .put("actionType", actionType)
            .put("label", label)
        is ChatMessage.ToolCallMessage -> obj.put("type", "tool_call")
            .put("toolName", toolName)
            .put("argsSummary", argsSummary)
        is ChatMessage.UiCardMessage -> obj.put("type", "ui_card")
            .put("cardType", cardType)
            .put("title", title)
            .put("body", body)
            .put("bullets", bullets.toStringJsonArray())
        is ChatMessage.DevLogMessage -> obj.put("type", "dev_log")
            .put("title", title)
            .put("detail", detail)
            .put("isError", isError)
    }
    return obj
}

private fun JSONObject.toChatMessage(): ChatMessage? {
    val id = optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
    val timestamp = optLong("timestamp", System.currentTimeMillis())
    val isFromUser = optBoolean("isFromUser", false)
    return when (optString("type")) {
        "text" -> ChatMessage.TextMessage(id, optString("text"), isFromUser, timestamp)
        "image_strip" -> ChatMessage.ImageStripMessage(
            id = id,
            imageResIds = optJSONArray("imageResIds")?.toIntList().orEmpty(),
            imagePaths = optJSONArray("imagePaths")?.toStringList().orEmpty(),
            label = optNullableString("label"),
            showDoranCheckAction = optBoolean("showDoranCheckAction", false),
            isFromUser = isFromUser,
            timestamp = timestamp
        )
        "audio" -> ChatMessage.AudioMessage(
            id = id,
            audioPath = optString("audioPath"),
            durationMs = optLong("durationMs", 0L),
            prompt = optNullableString("prompt"),
            isFromUser = isFromUser,
            timestamp = timestamp
        )
        "camera_widget" -> ChatMessage.CameraWidgetMessage(id, isFromUser, timestamp)
        "input_form" -> ChatMessage.InputFormWidgetMessage(
            id = id,
            params = optJSONObject("params")?.toDurianParameters() ?: DurianParameters(),
            mode = enumValueOrNull<InputFormMode>(optString("mode")) ?: InputFormMode.Active,
            status = enumValueOrNull<InputFormStatus>(optString("status")) ?: InputFormStatus.Pending,
            isFromUser = isFromUser,
            timestamp = timestamp
        )
        "result_report" -> ChatMessage.ResultReportMessage(
            id = id,
            paramsSnapshot = optJSONObject("paramsSnapshot")?.toDurianParameters() ?: DurianParameters(),
            imageResIds = optJSONArray("imageResIds")?.toIntList().orEmpty(),
            imagePaths = optJSONArray("imagePaths")?.toStringList().orEmpty(),
            score = optInt("score", 0),
            level = optInt("level", 0),
            reportText = optString("reportText"),
            isFromUser = isFromUser,
            timestamp = timestamp
        )
        "badge_unlocked" -> ChatMessage.BadgeUnlockedMessage(
            id = id,
            badgeId = optString("badgeId"),
            title = optString("title"),
            description = optString("description"),
            isFromUser = isFromUser,
            timestamp = timestamp
        )
        "analysis_progress" -> ChatMessage.AnalysisProgressMessage(
            id = id,
            title = optString("title", "视觉评测分析"),
            overallProgress = optDouble("overallProgress", 0.0).toFloat(),
            currentStepTitle = optNullableString("currentStepTitle"),
            steps = optJSONArray("steps")?.toAnalysisSteps().orEmpty(),
            interim = optJSONObject("interim")?.toStringMap().orEmpty(),
            canCancel = optBoolean("canCancel", true),
            isFromUser = isFromUser,
            timestamp = timestamp
        )
        "action" -> ChatMessage.ActionMessage(id, optString("actionType"), optString("label"), isFromUser, timestamp)
        "tool_call" -> ChatMessage.ToolCallMessage(
            id = id,
            toolName = optString("toolName"),
            argsSummary = optString("argsSummary"),
            isFromUser = isFromUser,
            timestamp = timestamp
        )
        "ui_card" -> ChatMessage.UiCardMessage(
            id = id,
            cardType = optString("cardType"),
            title = optString("title"),
            body = optString("body"),
            bullets = optJSONArray("bullets")?.toStringList().orEmpty(),
            isFromUser = isFromUser,
            timestamp = timestamp
        )
        "dev_log" -> ChatMessage.DevLogMessage(
            id = id,
            title = optString("title"),
            detail = optString("detail"),
            isError = optBoolean("isError", false),
            isFromUser = isFromUser,
            timestamp = timestamp
        )
        else -> null
    }
}

private fun AnalysisStep.toJson(): JSONObject {
    return JSONObject()
        .put("key", key)
        .put("title", title)
        .put("status", status.name)
        .putNullable("detail", detail)
}

private fun JSONObject.toAnalysisStep(): AnalysisStep {
    return AnalysisStep(
        key = optString("key"),
        title = optString("title"),
        status = enumValueOrNull<AnalysisStepStatus>(optString("status")) ?: AnalysisStepStatus.Pending,
        detail = optNullableString("detail")
    )
}

private fun JSONArray.toChatMessages(): List<ChatMessage> {
    return buildList {
        for (i in 0 until length()) {
            optJSONObject(i)?.toChatMessage()?.let { add(it) }
        }
    }
}

private fun JSONArray.toPhotoQualityProfiles(): List<PhotoQualityProfile> {
    return buildList {
        for (i in 0 until length()) {
            optJSONObject(i)?.toPhotoQualityProfile()?.let { add(it) }
        }
    }
}

private fun JSONArray.toAnalysisTraces(): List<AnalysisTrace> {
    return buildList {
        for (i in 0 until length()) {
            optJSONObject(i)?.toAnalysisTrace()?.let { add(it) }
        }
    }
}

private fun JSONArray.toReportActionSuggestions(): List<ReportActionSuggestion> {
    return buildList {
        for (i in 0 until length()) {
            optJSONObject(i)?.toReportActionSuggestion()?.let { add(it) }
        }
    }
}

private fun JSONArray.toAnalysisSteps(): List<AnalysisStep> {
    return buildList {
        for (i in 0 until length()) {
            optJSONObject(i)?.toAnalysisStep()?.let { add(it) }
        }
    }
}

private fun JSONArray.toStringList(): List<String> {
    return List(length()) { index -> optString(index) }.filter { it.isNotBlank() }
}

private fun JSONArray.toIntList(): List<Int> {
    return List(length()) { index -> optInt(index) }
}

private fun Iterable<String>.toStringJsonArray(): JSONArray {
    return JSONArray().also { arr -> forEach { arr.put(it) } }
}

private fun Iterable<Int>.toIntJsonArray(): JSONArray {
    return JSONArray().also { arr -> forEach { arr.put(it) } }
}

private fun Map<String, String>.toJsonObject(): JSONObject {
    return JSONObject().also { obj -> forEach { (key, value) -> obj.put(key, value) } }
}

private fun JSONObject.toStringMap(): Map<String, String> {
    return keys().asSequence().associateWith { key -> optString(key) }
}

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject {
    put(name, value ?: JSONObject.NULL)
    return this
}

private fun JSONObject.putOptString(name: String, value: String?): JSONObject {
    put(name, value ?: JSONObject.NULL)
    return this
}

private fun JSONObject.optNullableString(name: String): String? {
    return if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}

private inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? {
    if (name.isNullOrBlank() || name == "null") return null
    return enumValues<T>().firstOrNull { it.name == name }
}
