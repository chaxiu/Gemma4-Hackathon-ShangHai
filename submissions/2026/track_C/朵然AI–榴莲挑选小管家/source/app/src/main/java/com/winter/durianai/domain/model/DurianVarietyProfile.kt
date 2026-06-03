package com.winter.durianai.domain.model

import java.util.Locale

data class FloatRangeSpec(
    val min: Float,
    val max: Float
) {
    operator fun contains(value: Float): Boolean = value in min..max

    fun display(unit: String = ""): String {
        return "${min.trimZero()}~${max.trimZero()}$unit"
    }
}

data class DurianVarietyProfile(
    val variety: DurianVariety,
    val displayName: String,
    val typicalWeightKg: FloatRangeSpec,
    val idealWeightKg: FloatRangeSpec,
    val edibleRatioPercent: FloatRangeSpec,
    val idealLargeLobes: IntRange,
    val toleratedSmallLobes: IntRange,
    val preferredShapes: Set<DurianShape>,
    val shellThicknessTendency: String,
    val spikeDensityTendency: String,
    val spikeShapeHint: String,
    val maturityHints: List<String>,
    val riskHints: List<String>,
    val scoreBias: Int
)

object DurianVarietyProfiles {
    private val genericProfile = DurianVarietyProfile(
        variety = DurianVariety.OTHER,
        displayName = "通用榴莲",
        typicalWeightKg = FloatRangeSpec(1.0f, 4.5f),
        idealWeightKg = FloatRangeSpec(1.4f, 3.6f),
        edibleRatioPercent = FloatRangeSpec(15f, 30f),
        idealLargeLobes = 3..5,
        toleratedSmallLobes = 0..2,
        preferredShapes = setOf(DurianShape.ROUND, DurianShape.LYCHEE, DurianShape.OVAL),
        shellThicknessTendency = "中等",
        spikeDensityTendency = "中等",
        spikeShapeHint = "刺距均匀、主体饱满优先",
        maturityHints = listOf("果柄自然老化", "闻香不过冲", "轻敲有低沉回声"),
        riskHints = listOf("畸形明显", "小房过多", "重量与体积不匹配"),
        scoreBias = 0
    )

    private val profiles = mapOf(
        DurianVariety.MONTHONG to DurianVarietyProfile(
            variety = DurianVariety.MONTHONG,
            displayName = "金枕",
            typicalWeightKg = FloatRangeSpec(1.5f, 4.5f),
            idealWeightKg = FloatRangeSpec(2.0f, 3.8f),
            edibleRatioPercent = FloatRangeSpec(18f, 30f),
            idealLargeLobes = 3..5,
            toleratedSmallLobes = 0..2,
            preferredShapes = setOf(DurianShape.OVAL, DurianShape.LYCHEE),
            shellThicknessTendency = "中等偏厚",
            spikeDensityTendency = "中",
            spikeShapeHint = "刺距中等，果肩饱满比单纯圆度更重要",
            maturityHints = listOf("香气偏甜", "果柄略干但不枯", "果瓣线自然明显"),
            riskHints = listOf("过长且尾部尖瘦", "同体积明显偏轻", "小房过多"),
            scoreBias = 1
        ),
        DurianVariety.MUSANG_KING to DurianVarietyProfile(
            variety = DurianVariety.MUSANG_KING,
            displayName = "猫山王",
            typicalWeightKg = FloatRangeSpec(1.0f, 3.0f),
            idealWeightKg = FloatRangeSpec(1.3f, 2.4f),
            edibleRatioPercent = FloatRangeSpec(22f, 31f),
            idealLargeLobes = 4..5,
            toleratedSmallLobes = 0..1,
            preferredShapes = setOf(DurianShape.ROUND, DurianShape.LYCHEE),
            shellThicknessTendency = "中等偏薄",
            spikeDensityTendency = "中高",
            spikeShapeHint = "刺较密，底部星形和房线完整度需要重点看",
            maturityHints = listOf("底部星形清晰", "香气浓郁但不酒精味", "果形紧凑饱满"),
            riskHints = listOf("底部开裂过大", "壳面塌陷", "重量过大但房线不饱满"),
            scoreBias = 2
        ),
        DurianVariety.BLACK_THORN to DurianVarietyProfile(
            variety = DurianVariety.BLACK_THORN,
            displayName = "黑刺",
            typicalWeightKg = FloatRangeSpec(1.2f, 3.2f),
            idealWeightKg = FloatRangeSpec(1.5f, 2.8f),
            edibleRatioPercent = FloatRangeSpec(20f, 31f),
            idealLargeLobes = 4..5,
            toleratedSmallLobes = 0..1,
            preferredShapes = setOf(DurianShape.ROUND, DurianShape.LYCHEE),
            shellThicknessTendency = "中等偏薄",
            spikeDensityTendency = "中高",
            spikeShapeHint = "刺密且相对均匀，底部特征和圆整度权重较高",
            maturityHints = listOf("果形圆整", "香气浓厚", "底部特征自然完整"),
            riskHints = listOf("明显畸形", "底部异常开裂", "轻敲声音过空"),
            scoreBias = 2
        ),
        DurianVariety.PUANG_MANEE to DurianVarietyProfile(
            variety = DurianVariety.PUANG_MANEE,
            displayName = "托曼尼",
            typicalWeightKg = FloatRangeSpec(0.8f, 2.2f),
            idealWeightKg = FloatRangeSpec(1.0f, 1.8f),
            edibleRatioPercent = FloatRangeSpec(17f, 28f),
            idealLargeLobes = 3..5,
            toleratedSmallLobes = 0..2,
            preferredShapes = setOf(DurianShape.ROUND, DurianShape.LYCHEE),
            shellThicknessTendency = "中等",
            spikeDensityTendency = "中等偏密",
            spikeShapeHint = "个头通常不大，重点看房线和饱满度",
            maturityHints = listOf("香气清甜", "果型小而饱满", "房线清楚"),
            riskHints = listOf("个体过小且轻", "房线不明显", "畸形尾部"),
            scoreBias = 1
        ),
        DurianVariety.D24 to DurianVarietyProfile(
            variety = DurianVariety.D24,
            displayName = "D24",
            typicalWeightKg = FloatRangeSpec(1.2f, 3.5f),
            idealWeightKg = FloatRangeSpec(1.6f, 2.8f),
            edibleRatioPercent = FloatRangeSpec(17f, 29f),
            idealLargeLobes = 3..5,
            toleratedSmallLobes = 0..2,
            preferredShapes = setOf(DurianShape.ROUND, DurianShape.LYCHEE, DurianShape.OVAL),
            shellThicknessTendency = "中等",
            spikeDensityTendency = "中",
            spikeShapeHint = "刺距和房线均匀性优先，避免过度狭长",
            maturityHints = listOf("香气稳定", "果柄干湿适中", "房线自然"),
            riskHints = listOf("外形过扁", "小房偏多", "明显偏轻"),
            scoreBias = 1
        )
    )

    fun forVariety(variety: DurianVariety?): DurianVarietyProfile {
        return when (variety) {
            DurianVariety.AUTO, null -> genericProfile
            DurianVariety.OTHER -> genericProfile
            else -> profiles[variety] ?: genericProfile
        }
    }
}

private fun Float.trimZero(): String {
    return if (this % 1f == 0f) toInt().toString() else String.format(Locale.US, "%.1f", this)
}
