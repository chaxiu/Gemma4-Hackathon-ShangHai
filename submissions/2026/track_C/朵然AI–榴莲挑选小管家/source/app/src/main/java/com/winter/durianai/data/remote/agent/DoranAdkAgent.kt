package com.winter.durianai.data.remote.agent

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.winter.durianai.data.remote.llm.LlmRepository
import com.winter.durianai.domain.model.DurianParameters
import com.winter.durianai.domain.model.DurianShape
import com.winter.durianai.domain.model.DurianVariety
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject

data class DoranAdkRunResult(
    val reply: String,
    val actions: List<DoranAdkAction> = emptyList(),
    val rawModelText: String? = null
)

enum class DoranNavigationTarget {
    HISTORY,
    MODEL_MANAGER,
    SETTINGS,
    PROFILE,
    STATS,
    WIDGETS,
    ABOUT,
    LATEST_REPORT
}

sealed class DoranAdkAction {
    data class UpdateParameters(val params: DurianParameters) : DoranAdkAction()
    data class RequestInputForm(val reason: String? = null) : DoranAdkAction()
    data class RequestCameraCapture(val reason: String? = null) : DoranAdkAction()
    data class StartAnalysis(val reason: String? = null) : DoranAdkAction()
    data class Navigate(
        val target: DoranNavigationTarget,
        val reason: String? = null
    ) : DoranAdkAction()
    data class ShowUiCard(
        val cardType: String,
        val title: String,
        val body: String,
        val bullets: List<String> = emptyList()
    ) : DoranAdkAction()
    object RestartSelection : DoranAdkAction()
}

private data class DoranAgentTurnState(
    var params: DurianParameters,
    val latestUserText: String,
    val actions: MutableList<DoranAdkAction> = mutableListOf()
)

class DoranAdkAgent(
    private val llmRepository: LlmRepository
) {
    suspend fun runTurn(
        userText: String,
        currentParams: DurianParameters,
        photoState: String,
        sessionId: String,
        historySummary: String
    ): DoranAdkRunResult {
        val turnState = DoranAgentTurnState(params = currentParams, latestUserText = userText)
        val model = LiteRtToolCallingModel(
            llmRepository = llmRepository,
            currentParams = currentParams,
            photoState = photoState,
            historySummary = historySummary
        )
        val agent = LlmAgent(
            name = "doran_picker_agent",
            model = model,
            description = "Doran durian picking agent",
            instruction = Instruction(DORAN_AGENT_INSTRUCTION),
            tools = listOf(
                UpdateDurianParametersTool(turnState),
                RequestInputFormTool(turnState),
                RequestCameraCaptureTool(turnState),
                StartDurianAnalysisTool(turnState),
                NavigateAppScreenTool(turnState),
                ShowUiCardTool(turnState),
                RestartSelectionTool(turnState)
            ),
            includeContents = LlmAgent.IncludeContents.NONE
        )
        val runner = InMemoryRunner(agent = agent, appName = "doran_android")

        var finalReply = ""
        runner.runAsync(
            userId = "local-user",
            sessionId = sessionId.ifBlank { "default" },
            newMessage = Content.fromText(Role.USER, userText)
        ).collect { event ->
            val text = event.content?.parts
                ?.mapNotNull { it.text }
                ?.joinToString(separator = "\n")
                ?.trim()
                .orEmpty()
            if (text.isNotBlank()) {
                finalReply = text
            }
        }

        if (finalReply.isBlank()) {
            finalReply = when {
                turnState.actions.any { it is DoranAdkAction.RestartSelection } -> "好滴，我们重新开始看这颗榴莲。"
                turnState.actions.any { it is DoranAdkAction.RequestCameraCapture } -> "可以，我们先拍齐五个角度，我会用照片做质量门禁和视觉分析。"
                turnState.actions.any { it is DoranAdkAction.RequestInputForm } -> "好滴，我把参数表单放出来，你可以直接补充。"
                turnState.actions.any { it is DoranAdkAction.StartAnalysis } -> "收到，我来检查照片和参数，准备开始分析。"
                turnState.actions.any { it is DoranAdkAction.Navigate } -> "收到，我来帮你打开对应页面。"
                turnState.actions.any { it is DoranAdkAction.ShowUiCard } -> "收到，我整理成卡片给你看。"
                turnState.actions.isNotEmpty() -> "收到，我已经更新这颗榴莲的信息。"
                else -> "我刚刚没组织好回复，可以再说一次吗？"
            }
        }

        return DoranAdkRunResult(
            reply = finalReply,
            actions = turnState.actions.toList(),
            rawModelText = model.lastRawModelText
        )
    }

    private class LiteRtToolCallingModel(
        private val llmRepository: LlmRepository,
        private val currentParams: DurianParameters,
        private val photoState: String,
        private val historySummary: String
    ) : Model {
        override val name: String = "doran-litertlm-tool-model"
        var lastRawModelText: String? = null
            private set

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
            val functionResponse = request.contents
                .asReversed()
                .flatMap { it.parts }
                .firstOrNull { it.functionResponse != null }
                ?.functionResponse
            if (functionResponse != null) {
                val reply = functionResponse.response["reply"]?.toString()
                    ?: functionResponse.response["result"]?.toString()
                    ?: "收到，已经处理好了。"
                emit(textResponse(reply))
                return@flow
            }

            val systemPrompt = buildSystemPrompt(request)
            val userPrompt = buildUserPrompt(request)
            val raw = llmRepository.getChatCompletion(systemPrompt, userPrompt).trim()
            lastRawModelText = raw

            if (raw.startsWith("Error:")) {
                emit(textResponse(raw))
                return@flow
            }

            val toolCall = parseToolCall(raw, request)
            if (toolCall != null) {
                emit(
                    LlmResponse(
                        content = Content(
                            role = Role.MODEL,
                            parts = listOf(Part(functionCall = toolCall))
                        )
                    )
                )
                return@flow
            }

            emit(textResponse(parseReply(raw)))
        }

        private fun buildSystemPrompt(request: LlmRequest): String {
            val systemText = request.config.systemInstruction?.text().orEmpty()
            val toolsText = request.config.tools
                ?.flatMap { it.functionDeclarations.orEmpty() }
                ?.joinToString(separator = "\n") { declaration ->
                    "- ${declaration.name}: ${declaration.description} 参数=${declaration.parameters?.toPromptShape().orEmpty()}"
                }
                .orEmpty()
            return buildString {
                append(systemText.ifBlank { DORAN_AGENT_INSTRUCTION })
                append("\n\n当前参数：").append(currentParams.toAgentText())
                append("\n当前照片状态：").append(photoState)
                if (historySummary.isNotBlank()) {
                    append("\n\n最近上下文：\n").append(historySummary)
                }
                append("\n\n可用工具：\n").append(toolsText)
                append(
                    """

                    工具调用协议：
                    - 如果需要改变 App 状态，必须只输出 JSON，不要 Markdown。
                    - 调用工具时输出：
                      {"tool_call":{"name":"工具名","args":{...}},"reply":"给用户的简短说明"}
                    - 不调用工具、只回答榴莲相关问题时输出：
                      {"reply":"自然语言回答"}
                    - 如果用户问榴莲以外的话题，礼貌拒绝并引导回榴莲挑选。
                    - shape 使用 ROUND/LYCHEE/OVAL/IRREGULAR。
                    - variety 使用 AUTO/MONTHONG/MUSANG_KING/BLACK_THORN/PUANG_MANEE/D24/OTHER。
                    - 中文品种映射必须严格遵守：金枕=MONTHONG，猫山王=MUSANG_KING，黑刺=BLACK_THORN，托曼尼=PUANG_MANEE，D24=D24。
                    - 中文形态映射必须严格遵守：圆形=ROUND，荔枝形=LYCHEE，长椭圆形/椭圆=OVAL，不规则畸形/不规则=IRREGULAR。
                    - 用户只是比较/询问品种区别时不要调用 update_durian_parameters。
                    - 用户要求“开始分析/算出肉率/给报告/询问Doran/评估这颗”时调用 start_durian_analysis。
                    - 如果 start_durian_analysis 因缺照片或缺参数无法开始，App 会负责弹出相机或表单。
                    - 用户明确要求打开历史、模型管理、设置、报告详情、个人页、统计页、组件页时调用 navigate_app_screen。
                    - 如果你想把信息整理成结构化卡片给用户看，可以调用 show_ui_card。
                    - 如果用户在这一句话里明确给出了品种或形态，不要擅自改成别的枚举值。
                    """.trimIndent()
                )
            }
        }

        private fun buildUserPrompt(request: LlmRequest): String {
            return request.contents.joinToString(separator = "\n") { content ->
                val role = content.role ?: "unknown"
                val body = content.parts.joinToString(separator = "\n") { part ->
                    val text = part.text
                    val functionResponse = part.functionResponse
                    val functionCall = part.functionCall
                    when {
                        text != null -> text
                        functionResponse != null -> "tool_response(${functionResponse.name})=${functionResponse.response}"
                        functionCall != null -> "tool_call(${functionCall.name})=${functionCall.args}"
                        else -> ""
                    }
                }
                "$role: $body"
            }
        }

        private fun parseToolCall(raw: String, request: LlmRequest): FunctionCall? {
            val declaredNames = request.config.tools
                ?.flatMap { it.functionDeclarations.orEmpty() }
                ?.map { it.name }
                ?.toSet()
                .orEmpty()
            val normalizedRaw = normalizeStructuredJsonText(raw)
            val json = extractJsonObject(raw)
                ?.let(::normalizeStructuredJsonText)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }

            json?.optJSONObject("tool_call")?.let { call ->
                val name = call.optString("name").takeIf { it in declaredNames }
                    ?: return parseLooseToolCall(normalizedRaw, declaredNames)
                val args = call.optJSONObject("args")?.toMap().orEmpty()
                return FunctionCall(name = name, args = args)
            }

            val legacyIntent = json?.optString("intent")
            return when (legacyIntent) {
                "RESTART" -> FunctionCall(name = "restart_selection")
                "REQUEST_FORM" -> FunctionCall(name = "request_input_form")
                "START_ANALYSIS" -> FunctionCall(name = "start_durian_analysis")
                "UPDATE_PARAM" -> {
                    val args = mutableMapOf<String, Any?>()
                    if (!json.isNull("weight")) args["weightKg"] = json.optDouble("weight")
                    if (!json.isNull("largeLobes")) args["largeLobes"] = json.optInt("largeLobes")
                    if (!json.isNull("smallLobes")) args["smallLobes"] = json.optInt("smallLobes")
                    if (!json.isNull("shape_index")) {
                        DurianShape.values().getOrNull(json.optInt("shape_index"))?.let { args["shape"] = it.name }
                    }
                    if (!json.isNull("variety_index")) {
                        DurianVariety.values().getOrNull(json.optInt("variety_index"))?.let { args["variety"] = it.name }
                    }
                    FunctionCall(name = "update_durian_parameters", args = args)
                }
                else -> parseLooseToolCall(normalizedRaw, declaredNames)
            }
        }

        private fun parseReply(raw: String): String {
            val json = extractJsonObject(raw)
                ?.let(::normalizeStructuredJsonText)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val reply = json?.optString("reply")
                ?.replace("\\n", "\n")
                ?.takeIf { it.isNotBlank() }
            return when {
                reply != null -> reply
                json?.has("tool_call") == true -> "收到，我正在处理你的操作。"
                raw.looksLikeToolCallText() -> "收到，我正在处理你的操作。"
                else -> raw
            }
        }

        private fun textResponse(text: String): LlmResponse {
            return LlmResponse(
                content = Content(
                    role = Role.MODEL,
                    parts = listOf(Part(text = text))
                )
            )
        }
    }

    private class UpdateDurianParametersTool(
        private val state: DoranAgentTurnState
    ) : FunctionTool(
        name = "update_durian_parameters",
        description = "更新当前榴莲的结构化参数。只在用户明确提供或修正重量、房数、形态、品种时调用。"
    ) {
        override fun declaration(): FunctionDeclaration {
            return FunctionDeclaration(
                name = name,
                description = description,
                parameters = Schema(
                    type = Type.OBJECT,
                    properties = mapOf(
                        "weightKg" to Schema(type = Type.NUMBER, description = "重量，单位 kg"),
                        "largeLobes" to Schema(type = Type.INTEGER, description = "饱满大房数量"),
                        "smallLobes" to Schema(type = Type.INTEGER, description = "干瘪小房数量"),
                        "shape" to Schema(
                            type = Type.STRING,
                            enum = DurianShape.values().map { it.name },
                            description = "整体形态"
                        ),
                        "variety" to Schema(
                            type = Type.STRING,
                            enum = DurianVariety.values().map { it.name },
                            description = "榴莲品种"
                        )
                    )
                )
            )
        }

        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
            val explicitVariety = detectExplicitVarietyFromText(state.latestUserText)
            val explicitShape = detectExplicitShapeFromText(state.latestUserText)
            val next = state.params.copy(
                weightKg = args.floatOrNull("weightKg") ?: state.params.weightKg,
                largeLobes = args.intOrNull("largeLobes") ?: state.params.largeLobes,
                smallLobes = args.intOrNull("smallLobes") ?: state.params.smallLobes,
                shape = explicitShape ?: args.stringOrNull("shape")?.toDurianShape() ?: state.params.shape,
                variety = explicitVariety ?: args.stringOrNull("variety")?.toDurianVariety() ?: state.params.variety
            )
            state.params = next
            state.actions += DoranAdkAction.UpdateParameters(next)
            val missing = next.missingLabels()
            return mapOf(
                "reply" to if (missing.isEmpty()) {
                    "收到，我已经更新参数：${next.toShortText()}。现在可以结合五角度照片开始分析。"
                } else {
                    "收到，我已经更新参数：${next.toShortText()}。还缺：${missing.joinToString("、")}。"
                },
                "params" to next.toAgentText()
            )
        }
    }

    private class RequestInputFormTool(
        private val state: DoranAgentTurnState
    ) : FunctionTool(
        name = "request_input_form",
        description = "当用户想手动填写、修改、查看参数，或缺少必要参数时，在聊天中展示参数表单。"
    ) {
        override fun declaration(): FunctionDeclaration {
            return FunctionDeclaration(
                name = name,
                description = description,
                parameters = Schema(
                    type = Type.OBJECT,
                    properties = mapOf("reason" to Schema(type = Type.STRING, description = "展示表单的原因"))
                )
            )
        }

        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
            state.actions += DoranAdkAction.RequestInputForm(args.stringOrNull("reason"))
            return mapOf("reply" to "好滴，我把参数表单放出来。你可以直接补重量、房数、形态和品种。")
        }
    }

    private class RequestCameraCaptureTool(
        private val state: DoranAgentTurnState
    ) : FunctionTool(
        name = "request_camera_capture",
        description = "当用户想拍照、上传照片、开始视觉识别，或分析前缺少五角度照片时调用。"
    ) {
        override fun declaration(): FunctionDeclaration {
            return FunctionDeclaration(
                name = name,
                description = description,
                parameters = Schema(
                    type = Type.OBJECT,
                    properties = mapOf("reason" to Schema(type = Type.STRING, description = "需要拍摄的原因"))
                )
            )
        }

        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
            state.actions += DoranAdkAction.RequestCameraCapture(args.stringOrNull("reason"))
            return mapOf("reply" to "可以，我们先拍齐五个角度：上面、下面、左侧、右侧、正面。")
        }
    }

    private class StartDurianAnalysisTool(
        private val state: DoranAgentTurnState
    ) : FunctionTool(
        name = "start_durian_analysis",
        description = "当用户要求开始分析、计算出肉率、生成报告、评估这颗榴莲时调用。App 会先检查照片和参数是否齐全。"
    ) {
        override fun declaration(): FunctionDeclaration {
            return FunctionDeclaration(
                name = name,
                description = description,
                parameters = Schema(
                    type = Type.OBJECT,
                    properties = mapOf("reason" to Schema(type = Type.STRING, description = "用户想开始分析的原因"))
                )
            )
        }

        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
            state.actions += DoranAdkAction.StartAnalysis(args.stringOrNull("reason"))
            return mapOf("reply" to "收到，我来检查照片和参数；齐了就开始 Doran 动态视觉分析。")
        }
    }

    private class NavigateAppScreenTool(
        private val state: DoranAgentTurnState
    ) : FunctionTool(
        name = "navigate_app_screen",
        description = "当用户明确要求打开历史、模型管理、设置、个人页、统计页、组件页、关于页或最新报告时调用。"
    ) {
        override fun declaration(): FunctionDeclaration {
            return FunctionDeclaration(
                name = name,
                description = description,
                parameters = Schema(
                    type = Type.OBJECT,
                    properties = mapOf(
                        "target" to Schema(
                            type = Type.STRING,
                            enum = DoranNavigationTarget.values().map { it.name },
                            description = "目标页面"
                        ),
                        "reason" to Schema(type = Type.STRING, description = "打开页面的原因")
                    )
                )
            )
        }

        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
            val target = args.stringOrNull("target")
                ?.trim()
                ?.uppercase()
                ?.let { name -> DoranNavigationTarget.values().firstOrNull { it.name == name } }
                ?: return mapOf("reply" to "我没有识别到要打开的页面。")
            state.actions += DoranAdkAction.Navigate(target = target, reason = args.stringOrNull("reason"))
            return mapOf("reply" to "收到，我来帮你打开对应页面。")
        }
    }

    private class ShowUiCardTool(
        private val state: DoranAgentTurnState
    ) : FunctionTool(
        name = "show_ui_card",
        description = "把信息整理成结构化卡片，例如参数确认、视觉建议、下一步操作。"
    ) {
        override fun declaration(): FunctionDeclaration {
            return FunctionDeclaration(
                name = name,
                description = description,
                parameters = Schema(
                    type = Type.OBJECT,
                    properties = mapOf(
                        "cardType" to Schema(type = Type.STRING, description = "卡片类型，例如 PARAM_CONFIRMATION、VISUAL_GUIDANCE、NEXT_STEPS"),
                        "title" to Schema(type = Type.STRING, description = "卡片标题"),
                        "body" to Schema(type = Type.STRING, description = "卡片正文"),
                        "bullets" to Schema(
                            type = Type.ARRAY,
                            items = Schema(type = Type.STRING),
                            description = "卡片要点列表"
                        )
                    )
                )
            )
        }

        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
            val title = args.stringOrNull("title") ?: return mapOf("reply" to "卡片标题缺失。")
            val body = args.stringOrNull("body") ?: return mapOf("reply" to "卡片正文缺失。")
            state.actions += DoranAdkAction.ShowUiCard(
                cardType = args.stringOrNull("cardType") ?: "INFO",
                title = title,
                body = body,
                bullets = args.stringListOrEmpty("bullets")
            )
            return mapOf("reply" to body)
        }
    }

    private class RestartSelectionTool(
        private val state: DoranAgentTurnState
    ) : FunctionTool(
        name = "restart_selection",
        description = "当用户明确要求重新开始、重置当前榴莲、挑选其他榴莲时调用。"
    ) {
        override fun declaration(): FunctionDeclaration {
            return FunctionDeclaration(name = name, description = description)
        }

        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
            state.actions += DoranAdkAction.RestartSelection
            state.params = DurianParameters()
            return mapOf("reply" to "好滴，我们重新开始看这颗榴莲。")
        }
    }

    companion object {
        private const val DORAN_AGENT_INSTRUCTION =
            "你是朵然(Doran AI)，一个运行在 Android 端侧的榴莲挑选 Agent。你只处理榴莲挑选、品种、照片采集、参数补全、出肉率和本应用功能相关问题。你需要准确判断用户意图，并在需要改变 App 状态时调用工具。"
    }
}

private fun Content.text(): String {
    return parts.mapNotNull { it.text }.joinToString(separator = "\n").trim()
}

private fun DurianParameters.toAgentText(): String {
    return "重量=${weightKg ?: "未知"}kg, 大房=$largeLobes, 小房=$smallLobes, 形态=${shape?.name ?: "未知"}, 品种=${variety?.name ?: "未知"}"
}

private fun DurianParameters.toShortText(): String {
    val parts = mutableListOf<String>()
    weightKg?.let { parts += "重量 ${it}kg" }
    if (largeLobes > 0 || smallLobes > 0) parts += "房数 $largeLobes/$smallLobes"
    shape?.let { parts += "形态 ${it.displayName}" }
    variety?.let { parts += "品种 ${it.displayName}" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("，") ?: "暂未填写"
}

private fun DurianParameters.missingLabels(): List<String> {
    return buildList {
        if (weightKg == null) add("重量")
        if (largeLobes <= 0 && smallLobes <= 0) add("房数")
        if (shape == null) add("形态")
    }
}

private fun Schema.toPromptShape(): String {
    if (type != Type.OBJECT) return type?.name.orEmpty()
    return properties.orEmpty().entries.joinToString(prefix = "{", postfix = "}") { (key, schema) ->
        val enumText = schema.enum?.joinToString(prefix = "[", postfix = "]").orEmpty()
        "$key:${schema.type?.name.orEmpty()}$enumText"
    }
}

private fun Map<String, Any>.floatOrNull(key: String): Float? {
    return when (val value = this[key]) {
        is Number -> value.toFloat()
        is String -> value.toFloatOrNull()
        else -> null
    }
}

private fun Map<String, Any>.intOrNull(key: String): Int? {
    return when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}

private fun Map<String, Any>.stringOrNull(key: String): String? {
    return this[key]?.toString()?.takeIf { it.isNotBlank() }
}

private fun Map<String, Any>.stringListOrEmpty(key: String): List<String> {
    return when (val value = this[key]) {
        is List<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
        is Array<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
        else -> emptyList()
    }
}

private fun String.toDurianShape(): DurianShape? {
    val normalized = trim().uppercase()
    return when (trim()) {
        "椭圆", "长椭圆", "椭圆形", "长椭圆形" -> DurianShape.OVAL
        "不规则", "不规则形", "畸形", "不规则畸形" -> DurianShape.IRREGULAR
        else -> DurianShape.values().firstOrNull { it.name == normalized || it.displayName == trim() }
    }
}

private fun String.toDurianVariety(): DurianVariety? {
    val normalized = trim().uppercase()
    return DurianVariety.values().firstOrNull { it.name == normalized || it.displayName == trim() }
}

private fun detectExplicitVarietyFromText(text: String): DurianVariety? {
    val explicit = mapOf(
        "金枕" to DurianVariety.MONTHONG,
        "猫山王" to DurianVariety.MUSANG_KING,
        "黑刺" to DurianVariety.BLACK_THORN,
        "托曼尼" to DurianVariety.PUANG_MANEE,
        "D24" to DurianVariety.D24
    )
    val correctionPattern = Regex("""(?:是|改成|换成|设为|设成|就是|品种[：:=是]?)\s*(金枕|猫山王|黑刺|托曼尼|D24)""", RegexOption.IGNORE_CASE)
    correctionPattern.findAll(text).lastOrNull()?.groupValues?.getOrNull(1)?.let { label ->
        explicit.entries.firstOrNull { it.key.equals(label, ignoreCase = true) }?.value?.let { return it }
    }

    val hits = explicit.mapNotNull { (label, variety) ->
        val matched = if (label == "D24") {
            Regex("""\bD24\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        } else {
            text.contains(label)
        }
        val negated = listOf("不是$label", "不 $label", "不要$label", "非$label").any { text.contains(it) }
        if (matched && !negated) variety else null
    }.distinct()
    return hits.singleOrNull()
}

private fun detectExplicitShapeFromText(text: String): DurianShape? {
    val explicit = mapOf(
        "圆形" to DurianShape.ROUND,
        "荔枝形" to DurianShape.LYCHEE,
        "长椭圆形" to DurianShape.OVAL,
        "椭圆形" to DurianShape.OVAL,
        "椭圆" to DurianShape.OVAL,
        "不规则畸形" to DurianShape.IRREGULAR,
        "不规则" to DurianShape.IRREGULAR,
        "畸形" to DurianShape.IRREGULAR
    )
    val correctionPattern = Regex("""(?:是|改成|换成|设为|设成|就是|形态[：:=是]?)\s*(长椭圆形|椭圆形|椭圆|圆形|荔枝形|不规则畸形|不规则|畸形)""")
    correctionPattern.findAll(text).lastOrNull()?.groupValues?.getOrNull(1)?.let { label ->
        explicit[label]?.let { return it }
    }

    val hits = explicit.mapNotNull { (label, shape) ->
        val matched = text.contains(label)
        val negated = listOf("不是$label", "不 $label", "不要$label", "非$label").any { text.contains(it) }
        if (matched && !negated) shape else null
    }.distinct()
    return hits.singleOrNull()
}

private fun JSONObject.toMap(): Map<String, Any> {
    val out = mutableMapOf<String, Any>()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        if (isNull(key)) continue
        out[key] = jsonValueToKotlin(get(key))
    }
    return out
}

private fun jsonValueToKotlin(value: Any): Any {
    return when (value) {
        is JSONObject -> value.toMap()
        is JSONArray -> List(value.length()) { index -> jsonValueToKotlin(value.get(index)) }
        else -> value
    }
}

private fun extractJsonObject(text: String): String? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return text.substring(start, end + 1)
}

private fun normalizeStructuredJsonText(text: String): String {
    return text
        .replace("```json", "")
        .replace("```", "")
        .replace("\"/>", "\"")
        .replace("/>", "")
        .trim()
}

private fun String.looksLikeToolCallText(): Boolean {
    return contains("\"tool_call\"") || contains("{tool_call") || contains("tool_call:")
}

private fun parseLooseToolCall(text: String, declaredNames: Set<String>): FunctionCall? {
    if (declaredNames.isEmpty() || !text.looksLikeToolCallText()) return null
    val nameFromJsonish = Regex(""""name"\s*:\s*"([^"]+)"""")
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it in declaredNames }
    val name = nameFromJsonish ?: declaredNames.firstOrNull { declared ->
        text.contains(declared)
    } ?: return null

    val args = extractArgsObjectText(text)
        ?.let { runCatching { JSONObject(it).toMap() }.getOrNull() }
        .orEmpty()
    return FunctionCall(name = name, args = args)
}

private fun extractArgsObjectText(text: String): String? {
    val keyIndex = text.indexOf("\"args\"").takeIf { it >= 0 }
        ?: text.indexOf("args").takeIf { it >= 0 }
        ?: return null
    val start = text.indexOf('{', startIndex = keyIndex)
    if (start < 0) return null

    var depth = 0
    var inString = false
    var escaping = false
    for (index in start until text.length) {
        val ch = text[index]
        when {
            escaping -> escaping = false
            ch == '\\' && inString -> escaping = true
            ch == '"' -> inString = !inString
            !inString && ch == '{' -> depth++
            !inString && ch == '}' -> {
                depth--
                if (depth == 0) return text.substring(start, index + 1)
            }
        }
    }
    return null
}
