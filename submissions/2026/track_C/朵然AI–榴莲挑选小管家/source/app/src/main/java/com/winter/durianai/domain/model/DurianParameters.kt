package com.winter.durianai.domain.model

enum class DurianVariety(val displayName: String) {
    AUTO("Auto(AI识图)"),
    MONTHONG("金枕"),
    MUSANG_KING("猫山王"),
    BLACK_THORN("黑刺"),
    PUANG_MANEE("托曼尼"),
    D24("D24"),
    OTHER("其他")
}

enum class DurianShape(val displayName: String) {
    ROUND("圆形"),
    LYCHEE("荔枝形"),
    OVAL("长椭圆形"),
    IRREGULAR("不规则畸形")
}

data class DurianParameters(
    val weightKg: Float? = null,
    val largeLobes: Int = 0,
    val smallLobes: Int = 0,
    val shape: DurianShape? = null,
    val variety: DurianVariety? = null // 用户手动修正或选择的品种
) {
    fun isComplete(): Boolean {
        return weightKg != null && (largeLobes > 0 || smallLobes > 0) && shape != null
    }
}
