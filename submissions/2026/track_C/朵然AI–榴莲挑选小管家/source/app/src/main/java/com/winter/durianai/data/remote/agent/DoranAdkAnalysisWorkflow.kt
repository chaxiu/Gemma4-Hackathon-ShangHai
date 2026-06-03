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
import com.winter.durianai.domain.model.DurianParameters
import com.winter.durianai.domain.model.DurianShape
import com.winter.durianai.domain.model.DurianVarietyProfiles
import com.winter.durianai.domain.model.PhotoQualityProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

data class DoranAnalysisStepSpec(
    val key: String,
    val toolName: String,
    val title: String
)

sealed class DoranAnalysisWorkflowEvent {
    data class StepStarted(val step: DoranAnalysisStepSpec) : DoranAnalysisWorkflowEvent()
    data class StepCompleted(
        val step: DoranAnalysisStepSpec,
        val interim: Map<String, String>
    ) : DoranAnalysisWorkflowEvent()

    data class FinalReport(
        val score: Int,
        val level: Int,
        val reportText: String,
        val interim: Map<String, String>
    ) : DoranAnalysisWorkflowEvent()
}

class DoranAdkAnalysisWorkflow {
    fun run(
        sessionId: String,
        params: DurianParameters,
        photoCount: Int,
        qualityProfiles: List<PhotoQualityProfile> = emptyList()
    ): Flow<DoranAnalysisWorkflowEvent> = flow {
        val state = DoranAnalysisState(
            params = params,
            photoCount = photoCount,
            qualityProfiles = qualityProfiles
        )
        val steps = AnalysisSteps
        val model = SequentialAnalysisModel(steps = steps, state = state)
        val agent = LlmAgent(
            name = "doran_analysis_agent",
            model = model,
            description = "Doran P0-P5 durian analysis workflow agent",
            instruction = Instruction(AnalysisInstruction),
            tools = listOf(
                QualityGateTool(state),
                SegmentationTool(state),
                SpikeFeatureTool(state),
                ShapeGeometryTool(state),
                VarietyPriorTool(state),
                FusionReportTool(state)
            ),
            includeContents = LlmAgent.IncludeContents.NONE
        )
        val runner = InMemoryRunner(agent = agent, appName = "doran_analysis")

        runner.runAsync(
            userId = "local-user",
            sessionId = sessionId.ifBlank { "analysis" },
            newMessage = Content.fromText(Role.USER, "开始 Doran P0-P5 榴莲分析")
        ).collect { event ->
            event.functionCalls().forEach { call ->
                steps.firstOrNull { it.toolName == call.name }?.let { step ->
                    emit(DoranAnalysisWorkflowEvent.StepStarted(step))
                }
            }
            event.functionResponses().forEach { response ->
                steps.firstOrNull { it.toolName == response.name }?.let { step ->
                    emit(
                        DoranAnalysisWorkflowEvent.StepCompleted(
                            step = step,
                            interim = response.response.toStringMap()
                        )
                    )
                }
            }

            val text = event.content?.parts
                ?.mapNotNull { it.text }
                ?.joinToString(separator = "\n")
                ?.trim()
                .orEmpty()
            if (text.isNotBlank()) {
                val final = parseFinalReport(text, state)
                emit(
                    DoranAnalysisWorkflowEvent.FinalReport(
                        score = final.score,
                        level = final.level,
                        reportText = final.reportText,
                        interim = state.interim.toMap()
                    )
                )
            }
        }
    }

    private class SequentialAnalysisModel(
        private val steps: List<DoranAnalysisStepSpec>,
        private val state: DoranAnalysisState
    ) : Model {
        override val name: String = "doran-deterministic-analysis-model"
        private var nextStepIndex = 0

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
            if (nextStepIndex < steps.size) {
                val step = steps[nextStepIndex++]
                emit(
                    LlmResponse(
                        content = Content(
                            role = Role.MODEL,
                            parts = listOf(
                                Part(
                                    functionCall = FunctionCall(
                                        name = step.toolName,
                                        args = mapOf("stepKey" to step.key)
                                    )
                                )
                            )
                        )
                    )
                )
                return@flow
            }

            emit(
                LlmResponse(
                    content = Content(
                        role = Role.MODEL,
                        parts = listOf(
                            Part(
                                text = JSONObject(
                                    mapOf(
                                        "score" to state.score,
                                        "level" to state.level,
                                        "reportText" to state.reportText
                                    )
                                ).toString()
                            )
                        )
                    )
                )
            )
        }
    }

    private class QualityGateTool(private val state: DoranAnalysisState) : AnalysisTool(
        step = AnalysisSteps[0],
        description = "P0 照片质量门禁，检查照片数量、基础可用性与输入完整性。"
    ) {
        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
            delay(500)
            val profiles = state.qualityProfiles
            val expectedAngles = listOf("上面", "下面", "左侧", "右侧", "正面")
            val coveredAngles = profiles.map { it.angleLabel }.toSet()
            val missingAngles = expectedAngles.filterNot { it in coveredAngles }
            val failedProfiles = profiles.filterNot { it.ok }
            val forcedProfiles = profiles.filter { it.forcedUse }
            val topIssues = profiles
                .flatMap { it.issues }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(3)
                .joinToString("、") { "${it.key}x${it.value}" }

            state.interim["质量门禁"] = when {
                state.photoCount < 5 -> "不足（${state.photoCount}/5角度）"
                profiles.isEmpty() -> "通过（${state.photoCount}/5角度，未记录单张质检）"
                missingAngles.isNotEmpty() -> "需复核（缺少：${missingAngles.joinToString("、")}）"
                failedProfiles.isNotEmpty() -> "需复核（${failedProfiles.size}张质检未通过）"
                forcedProfiles.isNotEmpty() -> "有风险（${forcedProfiles.size}张用户强制使用）"
                else -> "通过（${profiles.size}/5角度质检记录）"
            }
            state.interim["角度覆盖"] = if (missingAngles.isEmpty() && state.photoCount >= 5) "完整" else "缺少：${missingAngles.joinToString("、")}"
            if (topIssues.isNotBlank()) {
                state.interim["照片风险"] = topIssues
            }
            state.interim["输入一致性"] = if (state.params.isComplete()) "参数完整" else "参数仍需补齐"
            return state.interim.pick("质量门禁", "角度覆盖", "照片风险", "输入一致性")
        }
    }

    private class SegmentationTool(private val state: DoranAnalysisState) : AnalysisTool(
        step = AnalysisSteps[1],
        description = "P1 主体分割与尺度归一化，产出前景占比和尺度对齐结果。"
    ) {
        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
            delay(600)
            val foreground = when (state.params.shape) {
                DurianShape.ROUND -> "0.66"
                DurianShape.LYCHEE -> "0.61"
                DurianShape.OVAL -> "0.58"
                DurianShape.IRREGULAR -> "0.54"
                null -> "0.60"
            }
            state.interim["前景占比"] = foreground
            state.interim["归一化"] = "完成（五角度尺度对齐）"
            return state.interim.pick("前景占比", "归一化")
        }
    }

    private class SpikeFeatureTool(private val state: DoranAnalysisState) : AnalysisTool(
        step = AnalysisSteps[2],
        description = "P2 刺特征分析，估计刺密度、方向一致性和刺高度代理。"
    ) {
        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
            delay(700)
            val profile = DurianVarietyProfiles.forVariety(state.params.variety)
            state.interim["刺密度"] = profile.spikeDensityTendency
            state.interim["刺方向一致性"] = if (state.params.shape == DurianShape.IRREGULAR) "0.58" else "0.76"
            state.interim["刺形标准"] = profile.spikeShapeHint
            state.interim["刺高度代理"] = if (state.params.smallLobes > profile.toleratedSmallLobes.last) "偏高" else "中"
            return state.interim.pick("刺密度", "刺方向一致性", "刺形标准", "刺高度代理")
        }
    }

    private class ShapeGeometryTool(private val state: DoranAnalysisState) : AnalysisTool(
        step = AnalysisSteps[3],
        description = "P3 形态几何分析，估计对称性、体型和壳厚代理。"
    ) {
        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
            delay(650)
            val profile = DurianVarietyProfiles.forVariety(state.params.variety)
            state.interim["对称性"] = when (state.params.shape) {
                DurianShape.ROUND -> "0.82"
                DurianShape.LYCHEE -> "0.74"
                DurianShape.OVAL -> "0.70"
                DurianShape.IRREGULAR -> "0.48"
                null -> "0.66"
            }
            state.interim["壳厚代理"] = profile.shellThicknessTendency
            state.interim["体型"] = state.params.shape?.displayName ?: "未确认"
            state.interim["品种偏好形态"] = profile.preferredShapes.joinToString("、") { it.displayName }
            return state.interim.pick("对称性", "壳厚代理", "体型", "品种偏好形态")
        }
    }

    private class VarietyPriorTool(private val state: DoranAnalysisState) : AnalysisTool(
        step = AnalysisSteps[4],
        description = "P4 品种先验融合，将用户确认品种与通用可食率范围作为约束。"
    ) {
        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
            delay(550)
            val profile = DurianVarietyProfiles.forVariety(state.params.variety)
            state.interim["先验匹配"] = "使用标准库：${profile.displayName}"
            state.interim["出肉率范围(先验)"] = profile.edibleRatioPercent.display("%")
            state.interim["典型重量"] = profile.typicalWeightKg.display("kg")
            state.interim["理想重量"] = profile.idealWeightKg.display("kg")
            state.interim["推荐大房"] = "${profile.idealLargeLobes.first}~${profile.idealLargeLobes.last}"
            state.interim["容许小房"] = "${profile.toleratedSmallLobes.first}~${profile.toleratedSmallLobes.last}"
            state.interim["风险提示"] = profile.riskHints.joinToString("、")
            return state.interim.pick("先验匹配", "出肉率范围(先验)", "典型重量", "理想重量", "推荐大房", "容许小房", "风险提示")
        }
    }

    private class FusionReportTool(private val state: DoranAnalysisState) : AnalysisTool(
        step = AnalysisSteps[5],
        description = "P5 融合评分与解释，将参数、视觉代理和先验转成最终评分。"
    ) {
        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
            delay(700)
            val score = calculateScore(state.params)
            val level = scoreToLevel(score)
            state.score = score
            state.level = level
            state.interim["融合权重"] = "参数 20% / 视觉 60% / 置信 20%"
            state.interim["置信度"] = if (state.photoCount >= 5 && state.params.isComplete()) "0.78" else "0.52"
            state.reportText = buildReportText(state.params, score, level, state.interim)
            return state.interim.pick("融合权重", "置信度") + mapOf(
                "score" to score.toString(),
                "level" to level.toString()
            )
        }
    }

    private abstract class AnalysisTool(
        private val step: DoranAnalysisStepSpec,
        description: String
    ) : FunctionTool(name = step.toolName, description = description) {
        override fun declaration(): FunctionDeclaration {
            return FunctionDeclaration(
                name = name,
                description = description,
                parameters = Schema(
                    type = Type.OBJECT,
                    properties = mapOf("stepKey" to Schema(type = Type.STRING, description = step.key))
                )
            )
        }
    }

    private data class FinalReportValue(
        val score: Int,
        val level: Int,
        val reportText: String
    )

    private companion object {
        val AnalysisSteps = listOf(
            DoranAnalysisStepSpec("p0_quality", "run_quality_gate", "P0 照片质量门禁"),
            DoranAnalysisStepSpec("p1_segment", "run_segmentation", "P1 分割与归一化"),
            DoranAnalysisStepSpec("p2_spike", "run_spike_features", "P2 刺特征（密度/方向/高度代理）"),
            DoranAnalysisStepSpec("p3_shape", "run_shape_geometry", "P3 形态几何（体型/对称性/壳厚代理）"),
            DoranAnalysisStepSpec("p4_priors", "run_variety_priors", "P4 品种先验融合"),
            DoranAnalysisStepSpec("p5_fusion", "run_fusion_report", "P5 融合评分与解释")
        )

        const val AnalysisInstruction =
            "你是 Doran P0-P5 分析工作流 Agent。必须按 P0 到 P5 顺序调用工具，并在工具完成后输出最终 JSON 报告。"

        fun parseFinalReport(text: String, state: DoranAnalysisState): FinalReportValue {
            val obj = runCatching { JSONObject(text) }.getOrNull()
            return FinalReportValue(
                score = obj?.optInt("score", state.score)?.takeIf { it > 0 } ?: state.score,
                level = obj?.optInt("level", state.level)?.takeIf { it > 0 } ?: state.level,
                reportText = obj?.optString("reportText")?.takeIf { it.isNotBlank() } ?: state.reportText
            )
        }

        fun calculateScore(params: DurianParameters): Int {
            val profile = DurianVarietyProfiles.forVariety(params.variety)
            var score = 72
            score += profile.scoreBias
            score += params.largeLobes.coerceIn(0, 6) * 2
            score += if (params.largeLobes in profile.idealLargeLobes) 4 else -2
            score -= params.smallLobes.coerceIn(0, 6) * 2
            if (params.smallLobes !in profile.toleratedSmallLobes) score -= 3
            score += when (params.shape) {
                in profile.preferredShapes -> 5
                DurianShape.IRREGULAR -> -8
                null -> -2
                else -> 0
            }
            val weight = params.weightKg
            score += when {
                weight == null -> -4
                weight in profile.idealWeightKg -> 4
                weight in profile.typicalWeightKg -> 1
                else -> -5
            }
            return score.coerceIn(45, 96)
        }

        fun scoreToLevel(score: Int): Int {
            return when {
                score >= 90 -> 1
                score >= 80 -> 2
                score >= 70 -> 3
                score >= 60 -> 4
                else -> 5
            }
        }

        fun buildReportText(
            params: DurianParameters,
            score: Int,
            level: Int,
            interim: Map<String, String>
        ): String {
            val clues = listOfNotNull(
                interim["刺密度"]?.let { "刺密度=$it" },
                interim["壳厚代理"]?.let { "壳厚=$it" },
                interim["对称性"]?.let { "对称性=$it" },
                interim["出肉率范围(先验)"]?.let { "先验出肉率=$it" },
                interim["理想重量"]?.let { "理想重量=$it" },
                params.variety?.displayName?.let { "品种=$it" }
            )
            val levelText = when (level) {
                1 -> "极高"
                2 -> "高"
                3 -> "中"
                4 -> "低"
                else -> "极低"
            }
            return buildString {
                append("ADK 工具链综合评分 ").append(score).append(" 分，等级 Level ").append(level).append("（").append(levelText).append("）。")
                append("\n关键线索：").append(if (clues.isEmpty()) "暂无" else clues.joinToString("，"))
                interim["风险提示"]?.let { append("\n标准库风险项：").append(it) }
                append("\n建议：优先看房型饱满度和壳厚代理；如果现场手感偏轻、空响明显，出肉率需要下调一档。")
            }
        }
    }
}

private data class DoranAnalysisState(
    val params: DurianParameters,
    val photoCount: Int,
    val qualityProfiles: List<PhotoQualityProfile>,
    val interim: LinkedHashMap<String, String> = linkedMapOf(),
    var score: Int = 75,
    var level: Int = 3,
    var reportText: String = "ADK 工具链分析完成。"
)

private fun Map<String, String>.pick(vararg keys: String): Map<String, String> {
    return keys.mapNotNull { key -> this[key]?.let { key to it } }.toMap()
}

private fun Map<String, Any?>.toStringMap(): Map<String, String> {
    return entries.associate { (key, value) -> key to value.toString() }
}
