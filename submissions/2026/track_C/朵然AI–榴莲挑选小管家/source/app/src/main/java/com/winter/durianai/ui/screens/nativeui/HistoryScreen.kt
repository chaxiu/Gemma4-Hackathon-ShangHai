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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winter.durianai.R
import com.winter.durianai.domain.model.PhotoSetStatus
import com.winter.durianai.ui.components.DurianEmoji
import com.winter.durianai.ui.screens.agent.AgentChatViewModel
import com.winter.durianai.ui.screens.agent.models.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HistoryScreen(
    agentChatViewModel: AgentChatViewModel,
    onNavigateBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenReport: (String) -> Unit
) {
    val sessions by agentChatViewModel.sessions.collectAsState()
    val tabs = listOf("全部", "猫山王", "D24", "金枕", "其他")
    val statusTabs = listOf("全部状态", "未拍照", "参数未完整", "可分析", "已分析", "照片无效")
    var selectedTab by remember { mutableStateOf(0) }
    var selectedStatusTab by remember { mutableStateOf(0) }
    var isEditMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showItemMenuFor by remember { mutableStateOf<String?>(null) }
    var showRenameSheetFor by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var showDeleteConfirmFor by remember { mutableStateOf<String?>(null) }
    var showDeleteSelectedConfirm by remember { mutableStateOf(false) }

    val filtered = remember(sessions, selectedTab, selectedStatusTab) {
        val target = tabs[selectedTab]
        sessions.filter { s ->
            val params = s.task.params
            val varietyMatched = when (target) {
                "全部" -> true
                "猫山王" -> params.variety?.displayName == "猫山王"
                "D24" -> params.variety?.displayName == "D24"
                "金枕" -> params.variety?.displayName == "金枕"
                "其他" -> params.variety == null || params.variety?.displayName in listOf("Auto(AI识图)", "其他")
                else -> true
            }
            val statusMatched = when (statusTabs[selectedStatusTab]) {
                "未拍照" -> s.task.photos.status == PhotoSetStatus.Missing
                "参数未完整" -> !params.isComplete()
                "可分析" -> s.task.photos.status == PhotoSetStatus.Ready && params.isComplete() && !s.hasAnalysisReport()
                "已分析" -> s.hasAnalysisReport()
                "照片无效" -> s.task.photos.status == PhotoSetStatus.Invalid
                else -> true
            }
            varietyMatched && statusMatched
        }
    }

    if (showRenameSheetFor != null) {
        val sessionId = showRenameSheetFor
        ModalBottomSheet(
            onDismissRequest = { showRenameSheetFor = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text("重命名", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text("会话名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (sessionId != null) {
                            agentChatViewModel.renameSession(sessionId, renameDraft)
                        }
                        showRenameSheetFor = null
                    },
                    enabled = sessionId != null && renameDraft.trim().isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存")
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showDeleteConfirmFor != null) {
        val sessionId = showDeleteConfirmFor
        AlertDialog(
            onDismissRequest = { showDeleteConfirmFor = null },
            title = { Text("删除会话？") },
            text = { Text("删除后将无法恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        if (sessionId != null) agentChatViewModel.deleteSession(sessionId)
                        showDeleteConfirmFor = null
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmFor = null }) { Text("取消") }
            }
        )
    }

    if (showDeleteSelectedConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedConfirm = false },
            title = { Text("删除已选会话？") },
            text = { Text("将删除 ${selectedIds.size} 个会话，且无法恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        selectedIds.forEach { agentChatViewModel.deleteSession(it) }
                        selectedIds = emptySet()
                        isEditMode = false
                        showDeleteSelectedConfirm = false
                    },
                    enabled = selectedIds.isNotEmpty()
                ) { Text(stringResource(id = R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedConfirm = false }) { Text(stringResource(id = R.string.action_cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.history_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.cd_back))
                    }
                },
                actions = {
                    if (isEditMode) {
                        IconButton(
                            onClick = { showDeleteSelectedConfirm = true },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                        TextButton(
                            onClick = {
                                isEditMode = false
                                selectedIds = emptySet()
                            }
                        ) { Text(stringResource(id = R.string.action_done)) }
                    } else {
                        TextButton(onClick = { isEditMode = true }) { Text(stringResource(id = R.string.action_edit)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 16.dp,
                divider = {},
                indicator = {}
            ) {
                tabs.forEachIndexed { index, title ->
                    val isSelected = selectedTab == index
                    Tab(
                        selected = isSelected,
                        onClick = { selectedTab = index },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = title,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            ScrollableTabRow(
                selectedTabIndex = selectedStatusTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 16.dp,
                divider = {},
                indicator = {}
            ) {
                statusTabs.forEachIndexed { index, title ->
                    val isSelected = selectedStatusTab == index
                    Tab(
                        selected = isSelected,
                        onClick = { selectedStatusTab = index },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = title,
                                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无历史记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filtered.asReversed(), key = { it.id }) { session ->
                        val isSelected = selectedIds.contains(session.id)
                        val latestReport = session.task.analysis.latestReport
                        val legacyResult = session.messages.asReversed()
                            .firstOrNull { it is ChatMessage.ResultReportMessage } as? ChatMessage.ResultReportMessage
                        val lastTs = session.messages.maxOfOrNull { it.timestamp }
                        val timeText = if (lastTs == null) {
                            "暂无对话"
                        } else {
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastTs))
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isEditMode) {
                                        selectedIds = if (isSelected) selectedIds - session.id else selectedIds + session.id
                                    } else {
                                        onOpenSession(session.id)
                                    }
                                },
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isEditMode) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = {
                                            selectedIds = if (isSelected) selectedIds - session.id else selectedIds + session.id
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                    DurianEmoji(size = 32.sp)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(session.title, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = when {
                                            latestReport != null -> "评分 ${latestReport.score} 分 · 等级 ${latestReport.level}"
                                            legacyResult != null -> "评分 ${legacyResult.score} 分 · 等级 ${legacyResult.level}"
                                            else -> "未分析"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = session.taskSummaryText(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                    val riskText = session.photoRiskSummary()
                                    if (riskText.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = riskText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = timeText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                }

                                if (!isEditMode) {
                                    Box {
                                        IconButton(onClick = { showItemMenuFor = session.id }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                                        }
                                        DropdownMenu(
                                            expanded = showItemMenuFor == session.id,
                                            onDismissRequest = { showItemMenuFor = null }
                                        ) {
                                            if (session.hasAnalysisReport()) {
                                                DropdownMenuItem(
                                                    text = { Text("查看报告") },
                                                    onClick = {
                                                        onOpenReport(session.id)
                                                        showItemMenuFor = null
                                                    }
                                                )
                                                HorizontalDivider()
                                            }
                                            DropdownMenuItem(
                                                text = { Text("重命名") },
                                                onClick = {
                                                    renameDraft = session.title
                                                    showRenameSheetFor = session.id
                                                    showItemMenuFor = null
                                                }
                                            )
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                                onClick = {
                                                    showDeleteConfirmFor = session.id
                                                    showItemMenuFor = null
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun com.winter.durianai.ui.screens.agent.ChatSession.hasAnalysisReport(): Boolean {
    return task.analysis.latestReport != null || messages.any { it is ChatMessage.ResultReportMessage }
}

private fun com.winter.durianai.ui.screens.agent.ChatSession.taskSummaryText(): String {
    val params = task.params
    val paramCount = listOf(
        params.weightKg != null,
        params.largeLobes > 0 || params.smallLobes > 0,
        params.shape != null
    ).count { it }
    val photoText = when (task.photos.status) {
        PhotoSetStatus.Ready -> "照片 ${task.photos.count}/5"
        PhotoSetStatus.Invalid -> "照片无效"
        PhotoSetStatus.Missing -> "照片 ${task.photos.count}/5"
    }
    val variety = params.variety?.displayName ?: "未选品种"
    return "$photoText · 参数 $paramCount/3 · $variety"
}

private fun com.winter.durianai.ui.screens.agent.ChatSession.photoRiskSummary(): String {
    val profiles = task.photos.qualityProfiles
    if (profiles.isEmpty()) return ""
    val failedCount = profiles.count { !it.ok || it.forcedUse }
    val topIssues = profiles
        .flatMap { it.issues }
        .filter { it.isNotBlank() && it != "other" }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(2)
        .joinToString("、") { issueLabel(it.key) }
    return when {
        failedCount > 0 && topIssues.isNotBlank() -> "质检风险：$topIssues"
        failedCount > 0 -> "质检风险：$failedCount 张需复核"
        topIssues.isNotBlank() -> "照片提示：$topIssues"
        else -> "五图质检通过"
    }
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
