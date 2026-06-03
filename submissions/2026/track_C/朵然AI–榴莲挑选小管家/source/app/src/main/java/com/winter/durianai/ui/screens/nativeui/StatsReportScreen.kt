package com.winter.durianai.ui.screens.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winter.durianai.R
import com.winter.durianai.domain.model.PhotoSetStatus
import com.winter.durianai.domain.model.ReportActionSuggestion
import com.winter.durianai.ui.components.DoranLoadingIndicator
import com.winter.durianai.ui.components.DurianEmoji
import com.winter.durianai.ui.screens.agent.AgentChatViewModel
import com.winter.durianai.ui.screens.agent.models.ChatMessage
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsReportScreen(
    agentChatViewModel: AgentChatViewModel,
    onNavigateBack: () -> Unit
) {
    val sessions by agentChatViewModel.sessions.collectAsState()
    var showFilterMenu by remember { mutableStateOf(false) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(250)
        isLoading = false
    }

    val filtered = if (selectedSessionId == null) sessions else sessions.filter { it.id == selectedSessionId }
    val analyzedCount = filtered.count { it.hasAnalysisReport() }
    val inProgressCount = filtered.count { sessionProgressCount(it) in 1..3 && !it.hasAnalysisReport() }
    val notStartedCount = filtered.count { sessionProgressCount(it) == 0 && !it.hasAnalysisReport() }
    val reports = filtered.mapNotNull { it.latestReportLike() }
    val averageScore = reports.takeIf { it.isNotEmpty() }?.map { it.score }?.average()
    val highRiskCount = reports.count { it.level >= 4 || it.score < 65 }
    val lowYieldCount = reports.count { it.level >= 4 }
    val varietyCounts = filtered
        .map { it.task.params.variety?.displayName ?: "未选品种" }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(5)
    val photoIssueCounts = filtered
        .flatMap { session ->
            val reportIssues = session.task.analysis.latestReport?.photoQualityProfiles.orEmpty().flatMap { it.issues }
            val taskIssues = session.task.photos.qualityProfiles.flatMap { it.issues }
            reportIssues + taskIssues
        }
        .filter { it.isNotBlank() && it != "other" }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(6)
    val suggestions = filtered.flatMap { it.latestSuggestions() }
    val suggestionCounts = suggestions
        .groupingBy { it.title }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(6)
    val decisionCounts = suggestions
        .filter { it.category == "decision" }
        .groupingBy { it.title }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(5)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.stats_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.cd_back))
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { showFilterMenu = true }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = sessions.firstOrNull { it.id == selectedSessionId }?.title ?: stringResource(id = R.string.stats_all_sessions),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(id = R.string.cd_filter))
                    }

                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(id = R.string.stats_all_sessions)) },
                            onClick = {
                                selectedSessionId = null
                                showFilterMenu = false
                            }
                        )
                        HorizontalDivider()
                        sessions.reversed().forEach { session ->
                            DropdownMenuItem(
                                text = { Text(session.title) },
                                onClick = {
                                    selectedSessionId = session.id
                                    showFilterMenu = false
                                },
                                leadingIcon = { DurianEmoji(size = 18.sp) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                DoranLoadingIndicator(indicatorSize = 28.dp)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Assessment, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.size(10.dp))
                            Text("会话汇总", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        StatLine("会话数", filtered.size.toString())
                        StatLine("已分析", analyzedCount.toString())
                        StatLine("进行中", inProgressCount.toString())
                        StatLine("未开始", notStartedCount.toString())
                        StatLine("平均评分", averageScore?.let { String.format("%.1f", it) } ?: "-")
                        StatLine("高风险", highRiskCount.toString())
                        StatLine("低出肉率拦截", lowYieldCount.toString())
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                StatListCard(
                    title = "品种分布",
                    emptyText = "暂无品种数据",
                    rows = varietyCounts.map { it.key to "${it.value} 次" }
                )

                Spacer(modifier = Modifier.height(16.dp))
                StatListCard(
                    title = "照片质检问题",
                    emptyText = "暂无照片问题",
                    rows = photoIssueCounts.map { issueLabel(it.key) to "${it.value} 次" }
                )

                Spacer(modifier = Modifier.height(16.dp))
                StatListCard(
                    title = "行动建议排行",
                    emptyText = "暂无行动建议",
                    rows = suggestionCounts.map { it.key to "${it.value} 次" }
                )

                Spacer(modifier = Modifier.height(16.dp))
                StatListCard(
                    title = "购买结论分布",
                    emptyText = "暂无购买结论",
                    rows = decisionCounts.map { it.key to "${it.value} 次" }
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text("按会话", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))

                if (filtered.isEmpty()) {
                    Text("暂无会话数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    filtered.reversed().forEachIndexed { index, session ->
                        SessionStatCard(
                            title = session.title,
                            status = sessionStatus(session),
                            suggestion = session.latestSuggestions().firstOrNull()?.let { "${it.title} · ${it.actionLabel ?: it.detail}" },
                            onClick = {
                                agentChatViewModel.switchSession(session.id)
                            }
                        )
                        if (index != filtered.lastIndex) Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatListCard(
    title: String,
    emptyText: String,
    rows: List<Pair<String, String>>
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            if (rows.isEmpty()) {
                Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                rows.forEach { (label, value) ->
                    StatLine(label, value)
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun SessionStatCard(
    title: String,
    status: String,
    suggestion: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                DurianEmoji(size = 20.sp)
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!suggestion.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        suggestion,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun sessionProgressCount(session: com.winter.durianai.ui.screens.agent.ChatSession): Int {
    val params = session.task.params
    val weightDone = params.weightKg != null
    val varietyDone = params.variety != null
    val shapeDone = params.shape != null
    val lobesDone = params.largeLobes > 0 || params.smallLobes > 0
    return listOf(weightDone, varietyDone, shapeDone, lobesDone).count { it }
}

private fun sessionStatus(session: com.winter.durianai.ui.screens.agent.ChatSession): String {
    val report = session.task.analysis.latestReport
    if (report != null) return "已分析 · ${report.score}分 / 等级 ${report.level}"
    val legacyReport = session.messages.lastOrNull { it is ChatMessage.ResultReportMessage } as? ChatMessage.ResultReportMessage
    if (legacyReport != null) return "已分析 · ${legacyReport.score}分 / 等级 ${legacyReport.level}"
    if (session.task.photos.status == PhotoSetStatus.Invalid) return "照片无效 · 需重拍"
    if (session.task.photos.status == PhotoSetStatus.Ready && session.task.params.isComplete()) return "可分析"
    val progress = sessionProgressCount(session)
    return if (progress == 0) "未开始" else "进行中 · $progress/4"
}

private fun com.winter.durianai.ui.screens.agent.ChatSession.hasAnalysisReport(): Boolean {
    return task.analysis.latestReport != null || messages.any { it is ChatMessage.ResultReportMessage }
}

private data class ReportLike(
    val score: Int,
    val level: Int
)

private fun com.winter.durianai.ui.screens.agent.ChatSession.latestSuggestions(): List<ReportActionSuggestion> {
    val report = task.analysis.latestReport
    if (report != null) {
        return report.suggestions.ifEmpty {
            fallbackReportSuggestions(report.score, report.level)
        }
    }
    val legacyReport = messages.lastOrNull { it is ChatMessage.ResultReportMessage } as? ChatMessage.ResultReportMessage
    return legacyReport?.let { fallbackReportSuggestions(it.score, it.level) }.orEmpty()
}

private fun com.winter.durianai.ui.screens.agent.ChatSession.latestReportLike(): ReportLike? {
    task.analysis.latestReport?.let { return ReportLike(score = it.score, level = it.level) }
    val legacyReport = messages.lastOrNull { it is ChatMessage.ResultReportMessage } as? ChatMessage.ResultReportMessage
    return legacyReport?.let { ReportLike(score = it.score, level = it.level) }
}

private fun fallbackReportSuggestions(score: Int, level: Int): List<ReportActionSuggestion> {
    return listOf(
        when {
            score <= 60 || level >= 5 -> ReportActionSuggestion(
                priority = 0,
                category = "decision",
                title = "不建议购买",
                detail = "综合评分偏低，建议换一颗。",
                actionLabel = "换一颗"
            )
            score < 70 || level >= 4 -> ReportActionSuggestion(
                priority = 1,
                category = "decision",
                title = "谨慎购买",
                detail = "存在低出肉率风险，建议现场复核。",
                actionLabel = "谨慎购买"
            )
            score >= 85 -> ReportActionSuggestion(
                priority = 3,
                category = "decision",
                title = "可以优先考虑",
                detail = "综合评分较高。",
                actionLabel = "可考虑"
            )
            else -> ReportActionSuggestion(
                priority = 2,
                category = "decision",
                title = "可作为候选",
                detail = "建议继续比较。",
                actionLabel = "继续比较"
            )
        }
    )
}

private fun issueLabel(issue: String): String {
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
