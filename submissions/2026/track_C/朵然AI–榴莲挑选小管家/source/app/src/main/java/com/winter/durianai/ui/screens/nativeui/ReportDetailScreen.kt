package com.winter.durianai.ui.screens.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.winter.durianai.domain.model.AnalysisReport
import com.winter.durianai.domain.model.AnalysisTrace
import com.winter.durianai.domain.model.DurianParameters
import com.winter.durianai.domain.model.DurianVarietyProfiles
import com.winter.durianai.domain.model.PhotoQualityProfile
import com.winter.durianai.domain.model.ReportActionSuggestion
import com.winter.durianai.ui.screens.agent.AgentChatViewModel
import com.winter.durianai.ui.screens.agent.models.ChatMessage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDetailScreen(
    agentChatViewModel: AgentChatViewModel,
    sessionId: String,
    onNavigateBack: () -> Unit
) {
    val sessions by agentChatViewModel.sessions.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    val report = session?.task?.analysis?.latestReport
        ?: (session?.messages?.asReversed()?.firstOrNull { it is ChatMessage.ResultReportMessage } as? ChatMessage.ResultReportMessage)
            ?.toAnalysisReport()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分析报告", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        if (session == null || report == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无可查看的报告", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ReportScoreHeader(report)
            ActionSuggestionsSection(report.suggestions.ifEmpty { fallbackSuggestions(report) })
            ParameterSection(report.paramsSnapshot)
            PhotoQualitySection(report.photoQualityProfiles.ifEmpty { session.task.photos.qualityProfiles })
            VarietyStandardSection(report.paramsSnapshot)
            TraceSection(report.trace, report.interim)
            ReportTextSection(report.reportText)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ActionSuggestionsSection(suggestions: List<ReportActionSuggestion>) {
    SectionCard(title = "下一步建议") {
        suggestions.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(suggestionColor(item.priority).copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        categoryLabel(item.category),
                        style = MaterialTheme.typography.labelSmall,
                        color = suggestionColor(item.priority),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        item.actionLabel?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = suggestionColor(item.priority),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (index != suggestions.lastIndex) HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
        }
    }
}

@Composable
private fun ReportScoreHeader(report: AnalysisReport) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text("${report.score}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Level ${report.level}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(report.createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ParameterSection(params: DurianParameters) {
    SectionCard(title = "参数快照") {
        InfoRow("品种", params.variety?.displayName ?: "未标注")
        InfoRow("重量", params.weightKg?.let { "${it}kg" } ?: "未填写")
        InfoRow("房型", "大房 ${params.largeLobes} · 小房 ${params.smallLobes}")
        InfoRow("形态", params.shape?.displayName ?: "未填写")
    }
}

@Composable
private fun PhotoQualitySection(profiles: List<PhotoQualityProfile>) {
    SectionCard(title = "五图质检") {
        if (profiles.isEmpty()) {
            Text("暂无照片质检记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            profiles.forEachIndexed { index, profile ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (profile.imagePath.isNotBlank() && File(profile.imagePath).exists()) {
                        AsyncImage(
                            model = File(profile.imagePath),
                            contentDescription = profile.angleLabel,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .aspectRatio(1f),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Icon(
                        imageVector = if (profile.ok && !profile.forcedUse) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = if (profile.ok && !profile.forcedUse) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.angleLabel.ifBlank { "角度 ${index + 1}" }, fontWeight = FontWeight.Bold)
                        Text(
                            profile.reason.ifBlank { if (profile.ok) "质检通过" else "需要复核" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (profile.issues.isNotEmpty()) {
                            Text(
                                profile.issues.joinToString("、") { reportIssueLabel(it) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                if (index != profiles.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun VarietyStandardSection(params: DurianParameters) {
    val profile = DurianVarietyProfiles.forVariety(params.variety)
    SectionCard(title = "品种标准库对比") {
        InfoRow("标准品种", profile.displayName)
        InfoRow("典型重量", profile.typicalWeightKg.display("kg"))
        InfoRow("理想重量", profile.idealWeightKg.display("kg"))
        InfoRow("先验出肉率", profile.edibleRatioPercent.display("%"))
        InfoRow("理想大房", "${profile.idealLargeLobes.first}~${profile.idealLargeLobes.last}")
        InfoRow("可容忍小房", "${profile.toleratedSmallLobes.first}~${profile.toleratedSmallLobes.last}")
        InfoRow("壳厚倾向", profile.shellThicknessTendency)
        InfoRow("刺特征", profile.spikeShapeHint)
        if (profile.riskHints.isNotEmpty()) {
            InfoRow("常见风险", profile.riskHints.joinToString("、"))
        }
    }
}

@Composable
private fun TraceSection(
    trace: List<AnalysisTrace>,
    interim: Map<String, String>
) {
    SectionCard(title = "Agent 工具链") {
        if (trace.isEmpty()) {
            if (interim.isEmpty()) {
                Text("暂无工具链回放记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                interim.entries.forEachIndexed { index, entry ->
                    InfoRow(entry.key, entry.value)
                    if (index != interim.size - 1) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        } else {
            trace.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Timeline, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Bold)
                        Text(item.toolName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        item.output.entries.take(5).forEach { (key, value) ->
                            Text(
                                "$key：$value",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (index != trace.lastIndex) HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun ReportTextSection(text: String) {
    SectionCard(title = "报告解读") {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

private fun ChatMessage.ResultReportMessage.toAnalysisReport(): AnalysisReport {
    return AnalysisReport(
        id = id,
        paramsSnapshot = paramsSnapshot,
        imagePaths = imagePaths,
        score = score,
        level = level,
        reportText = reportText,
        createdAt = timestamp
    )
}

private fun fallbackSuggestions(report: AnalysisReport): List<ReportActionSuggestion> {
    return listOf(
        when {
            report.score <= 60 || report.level >= 5 -> ReportActionSuggestion(
                priority = 0,
                category = "decision",
                title = "不建议购买",
                detail = "综合评分偏低，建议换一颗或要求开果确认。",
                actionLabel = "换一颗"
            )
            report.score < 70 || report.level >= 4 -> ReportActionSuggestion(
                priority = 1,
                category = "decision",
                title = "谨慎购买",
                detail = "存在一定低出肉率风险，建议和现场价格、开果保障一起判断。",
                actionLabel = "谨慎购买"
            )
            report.score >= 85 -> ReportActionSuggestion(
                priority = 3,
                category = "decision",
                title = "可以优先考虑",
                detail = "综合评分较高，现场再确认香气、果柄和敲击声音即可。",
                actionLabel = "可考虑"
            )
            else -> ReportActionSuggestion(
                priority = 2,
                category = "decision",
                title = "可作为候选",
                detail = "综合表现中等，建议继续和其他榴莲比较。",
                actionLabel = "继续比较"
            )
        }
    )
}

@Composable
private fun suggestionColor(priority: Int): androidx.compose.ui.graphics.Color {
    return when {
        priority <= 0 -> MaterialTheme.colorScheme.error
        priority == 1 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
}

private fun categoryLabel(category: String): String {
    return when (category) {
        "photo" -> "图"
        "parameter" -> "参"
        "yield" -> "肉"
        "risk" -> "险"
        "system" -> "系"
        else -> "买"
    }
}

private fun reportIssueLabel(issue: String): String {
    return when (issue) {
        "wrong_angle" -> "角度不符"
        "occluded" -> "遮挡"
        "cluttered" -> "杂物多"
        "too_far" -> "主体太远"
        "cropped" -> "裁切"
        "blurry" -> "模糊"
        "dark" -> "过暗"
        "overexposed" -> "过曝"
        "glare" -> "反光"
        "non_durian" -> "非榴莲"
        else -> issue
    }
}
