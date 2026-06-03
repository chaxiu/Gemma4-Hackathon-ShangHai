package com.winter.durianai.ui.screens.nativeui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winter.durianai.R
import com.winter.durianai.domain.model.AnalysisTaskStatus
import com.winter.durianai.domain.model.DurianParameters
import com.winter.durianai.domain.model.PhotoSetStatus
import com.winter.durianai.ui.components.DurianEmoji
import com.winter.durianai.ui.components.FloatingMorphIcon
import com.winter.durianai.ui.screens.agent.AgentChatViewModel
import com.winter.durianai.ui.screens.agent.ChatSession
import com.winter.durianai.ui.screens.agent.models.ChatMessage
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    appViewModel: com.winter.durianai.ui.AppViewModel? = null,
    agentChatViewModel: AgentChatViewModel,
    onOpenDrawer: () -> Unit,
    onNavigateToAgent: () -> Unit,
    onNavigateToAchievements: () -> Unit,
    onOpenLatestReport: (String) -> Unit,
    onOpenFloatingBall: () -> Unit = {}
) {
    val sessions by agentChatViewModel.sessions.collectAsState()
    var showFloatingBallDialog by remember { mutableStateOf(false) }
    val floatingBallDescription = stringResource(id = R.string.floating_ball_cd)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.doran_logo),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Doran AI Edge", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = { showFloatingBallDialog = true }) {
                        FloatingMorphIcon(
                            modifier = Modifier.semantics {
                                contentDescription = floatingBallDescription
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Blurred gradient background like Google AI Edge Gallery
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .align(Alignment.TopCenter)
                    .alpha(0.15f)
                    .blur(radius = 60.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                Color.Transparent
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "朵然 · 榴莲挑选助手",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(32.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedDoranSparkles()
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Try Doran today",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Doran is here! Try it in AI Chat to pick your best durian.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(24.dp))

                val activeSession = sessions.lastOrNull { it.hasMeaningfulTask() }
                TodayTaskOverview(
                    session = activeSession,
                    onContinue = {
                        activeSession?.id?.let { agentChatViewModel.switchSession(it) }
                        onNavigateToAgent()
                    },
                    onNewTask = {
                        agentChatViewModel.createNewSession()
                        onNavigateToAgent()
                    }
                )

                activeSession?.latestDashboardReport()?.let { report ->
                    Spacer(modifier = Modifier.height(16.dp))
                    LatestReportSummaryCard(
                        report = report,
                        onClick = { onOpenLatestReport(activeSession.id) }
                    )
                }

                Spacer(modifier = Modifier.height(36.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "最近任务",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = onNavigateToAchievements,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("成就徽章")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                RecentTasksList(
                    sessions = sessions.filter { it.hasMeaningfulTask() },
                    onOpenSession = { sessionId ->
                        agentChatViewModel.switchSession(sessionId)
                        onNavigateToAgent()
                    }
                )
                
                Spacer(modifier = Modifier.height(64.dp))
            }
        }
    }

    if (showFloatingBallDialog) {
        FloatingBallIntroDialog(
            onDismiss = { showFloatingBallDialog = false },
            onConfirm = {
                showFloatingBallDialog = false
                onOpenFloatingBall()
            }
        )
    }
}

@Composable
private fun FloatingBallIntroDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.doran_logo),
                    contentDescription = null,
                    modifier = Modifier.size(34.dp)
                )
            }
        },
        title = {
            Text(
                text = stringResource(id = R.string.floating_ball_dialog_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(id = R.string.floating_ball_dialog_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(id = R.string.floating_ball_dialog_permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.action_cancel))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = stringResource(id = R.string.floating_ball_dialog_confirm))
            }
        }
    )
}

@Composable
private fun AnimatedDoranSparkles() {
    val transition = rememberInfiniteTransition(label = "doranSparkles")
    val mainScale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 820),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mainSparkleScale"
    )
    val smallScale by transition.animateFloat(
        initialValue = 1.18f,
        targetValue = 0.78f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 980),
            repeatMode = RepeatMode.Reverse
        ),
        label = "smallSparkleScale"
    )
    val tinyScale by transition.animateFloat(
        initialValue = 0.76f,
        targetValue = 1.24f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1160),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tinySparkleScale"
    )

    Box(modifier = Modifier.size(30.dp)) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer(scaleX = mainScale, scaleY = mainScale)
                .size(22.dp)
        )
        Icon(
            Icons.Default.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .graphicsLayer(scaleX = smallScale, scaleY = smallScale, alpha = smallScale.coerceIn(0.65f, 1f))
                .size(9.dp)
        )
        Icon(
            Icons.Default.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .graphicsLayer(scaleX = tinyScale, scaleY = tinyScale, alpha = tinyScale.coerceIn(0.7f, 1f))
                .size(7.dp)
        )
    }
}

@Composable
fun DashboardCard(
    title: String,
    titleLeading: (@Composable () -> Unit)? = null,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "scale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp, pressedElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (titleLeading != null) {
                        titleLeading()
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun TodayTaskOverview(
    session: ChatSession?,
    onContinue: () -> Unit,
    onNewTask: () -> Unit
) {
    val params = session?.task?.params ?: DurianParameters()
    val photoCount = session?.task?.photos?.count ?: 0
    val photoStatus = session?.task?.photos?.status ?: PhotoSetStatus.Missing
    val report = session?.latestDashboardReport()
    val completeFields = params.dashboardCompleteFieldCount()
    val continueText = when {
        session == null -> "开始挑选"
        report != null -> "查看报告"
        photoStatus == PhotoSetStatus.Invalid -> "重拍照片"
        photoCount < 5 -> "继续拍照"
        completeFields < 4 -> "补全参数"
        else -> "开始分析"
    }
    val subtitle = when {
        session == null -> "还没有任务，先建立一个新的挑选流程。"
        report != null -> "上次分析 ${report.score} 分 · Level ${report.level}"
        photoStatus == PhotoSetStatus.Invalid -> "照片质检未通过，需要重拍关键角度。"
        else -> "照片 $photoCount/5 · 参数 $completeFields/4"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("今日任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (session != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskMetricPill("照片", "$photoCount/5", photoStatus != PhotoSetStatus.Invalid, Modifier.weight(1f))
                    TaskMetricPill("参数", "$completeFields/4", completeFields >= 4, Modifier.weight(1f))
                    TaskMetricPill("分析", if (report == null) "待完成" else "${report.score}分", report != null, Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(continueText, maxLines = 1)
                }
                Button(
                    onClick = onNewTask,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text("新挑一个", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TaskMetricPill(
    label: String,
    value: String,
    positive: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (positive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LatestReportSummaryCard(
    report: DashboardReportLike,
    onClick: () -> Unit
) {
    val riskText = report.riskText.ifBlank { "暂无明显风险" }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text("${report.score}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("上次报告 · Level ${report.level}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${report.varietyText} · $riskText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecentSessionsCard(
    sessions: List<com.winter.durianai.ui.screens.agent.ChatSession>,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.9f),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "最近会话",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            val recent = sessions.takeLast(3).reversed()
            if (recent.isEmpty()) {
                Text(text = "还没有会话记录", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                recent.forEachIndexed { index, session ->
                    SessionRow(
                        title = session.title,
                        status = sessionStatusText(session),
                        dateText = sessionDateText(session),
                        onClick = { onOpenSession(session.id) }
                    )
                    if (index != recent.lastIndex) {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    title: String,
    status: String,
    dateText: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            DurianEmoji(size = 18.sp)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = dateText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecentTasksList(
    sessions: List<ChatSession>,
    onOpenSession: (String) -> Unit
) {
    val recent = sessions.takeLast(6).reversed()
    if (recent.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Text(
                text = "还没有任务记录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            recent.forEach { session ->
                RecentTaskCard(session = session, onClick = { onOpenSession(session.id) })
            }
        }
    }
}

@Composable
private fun RecentTaskCard(
    session: ChatSession,
    onClick: () -> Unit
) {
    val params = session.task.params
    val paramCount = params.dashboardCompleteFieldCount()
    val report = session.latestDashboardReport()
    val riskText = report?.riskText?.takeIf { it.isNotBlank() } ?: session.dashboardPhotoRiskText()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    DurianEmoji(size = 24.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(session.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "${params.variety?.displayName ?: "未选品种"} · ${sessionDateText(session)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            RecentTaskStepProgress(session = session, paramCount = paramCount, report = report)

            if (report != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "报告：${report.score}分 · Level ${report.level} · ${report.varietyText}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (riskText.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "风险：$riskText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun RecentTaskStepProgress(
    session: ChatSession,
    paramCount: Int,
    report: DashboardReportLike?
) {
    val photoDone = session.task.photos.status == PhotoSetStatus.Ready
    val photoInvalid = session.task.photos.status == PhotoSetStatus.Invalid
    val paramsDone = paramCount >= 4
    val analysisDone = report != null
    val steps = listOf(
        Triple("拍摄", photoDone, if (photoInvalid) "无效" else "${session.task.photos.count}/5"),
        Triple("参数", paramsDone, "$paramCount/4"),
        Triple("分析", analysisDone, session.dashboardAnalysisStatus())
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { index, (label, done, sub) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                done -> MaterialTheme.colorScheme.primary
                                label == "拍摄" && photoInvalid -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = if (done) FontWeight.Bold else FontWeight.Normal)
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (index != steps.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(0.36f)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
    }
}

private fun sessionStatusText(session: com.winter.durianai.ui.screens.agent.ChatSession): String {
    val report = session.task.analysis.latestReport
    if (report != null) return "已分析 · ${report.score}分"

    val legacyReport = session.messages.lastOrNull { it is ChatMessage.ResultReportMessage } as? ChatMessage.ResultReportMessage
    if (legacyReport != null) return "已分析 · ${legacyReport.score}分"

    val params = session.task.params
    val weightDone = params.weightKg != null
    val varietyDone = params.variety != null
    val shapeDone = params.shape != null
    val lobesDone = params.largeLobes > 0 || params.smallLobes > 0
    val doneCount = listOf(weightDone, varietyDone, shapeDone, lobesDone).count { it }
    val photoText = when (session.task.photos.status) {
        PhotoSetStatus.Invalid -> "照片无效"
        PhotoSetStatus.Ready -> "照片 5/5"
        PhotoSetStatus.Missing -> "照片 ${session.task.photos.count}/5"
    }

    return if (doneCount == 0 && session.task.photos.count == 0) "未开始" else "$photoText · 参数 $doneCount/4"
}

private fun sessionDateText(session: com.winter.durianai.ui.screens.agent.ChatSession): String {
    val ts = session.messages.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()
    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())
    return DateTimeFormatter.ofPattern("yyyy-MM-dd").format(dt)
}

private data class DashboardReportLike(
    val score: Int,
    val level: Int,
    val varietyText: String,
    val riskText: String
)

private fun ChatSession.hasMeaningfulTask(): Boolean {
    val taskParams = task.params
    val legacyParams = params
    val hasParams = taskParams.dashboardCompleteFieldCount() > 0 || legacyParams.dashboardCompleteFieldCount() > 0
    val hasPhotos = task.photos.count > 0 || task.photos.status != PhotoSetStatus.Missing
    val hasAnalysis = task.analysis.status != AnalysisTaskStatus.Idle ||
        task.analysis.score != null ||
        task.analysis.latestReport != null
    val hasUserInput = messages.any { it is ChatMessage.TextMessage && it.isFromUser }
    val hasCapturedImages = messages.any {
        it is ChatMessage.ImageStripMessage && (it.imagePaths.isNotEmpty() || it.imageResIds.isNotEmpty())
    }
    val hasLegacyReport = messages.any { it is ChatMessage.ResultReportMessage }
    return hasParams || hasPhotos || hasAnalysis || hasUserInput || hasCapturedImages || hasLegacyReport
}

private fun ChatSession.dashboardAnalysisStatus(): String {
    latestDashboardReport()?.let { return "${it.score}分" }
    return when (task.analysis.status) {
        AnalysisTaskStatus.Running -> "分析中"
        AnalysisTaskStatus.Failed -> "失败"
        AnalysisTaskStatus.Cancelled -> "已取消"
        AnalysisTaskStatus.Completed -> task.analysis.score?.let { "${it}分" } ?: "已完成"
        AnalysisTaskStatus.Idle -> {
            when {
                task.photos.status == PhotoSetStatus.Invalid -> "待重拍"
                task.photos.status == PhotoSetStatus.Ready && task.params.isComplete() -> "可分析"
                else -> "待完成"
            }
        }
    }
}

private fun ChatSession.dashboardPhotoRiskText(): String {
    val profiles = task.photos.qualityProfiles
    if (task.photos.status == PhotoSetStatus.Invalid) {
        return task.photos.invalidReason ?: "照片质检未通过"
    }
    val topIssues = profiles
        .flatMap { it.issues }
        .filter { it.isNotBlank() && it != "other" }
        .distinct()
        .take(2)
        .joinToString("、") { dashboardIssueLabel(it) }
    return when {
        topIssues.isNotBlank() -> topIssues
        profiles.any { !it.ok || it.forcedUse } -> "有照片需要复核"
        else -> ""
    }
}

private fun ChatSession.latestDashboardReport(): DashboardReportLike? {
    task.analysis.latestReport?.let { report ->
        val risks = buildList {
            report.interim["风险提示"]?.takeIf { it.isNotBlank() }?.let { add(it) }
            report.suggestions.firstOrNull { it.priority <= 1 }?.let { add(it.title) }
            report.photoQualityProfiles
                .flatMap { it.issues }
                .distinct()
                .take(2)
                .map { dashboardIssueLabel(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { add("照片风险 ${it.joinToString("、")}") }
        }.joinToString("；")
        return DashboardReportLike(
            score = report.score,
            level = report.level,
            varietyText = report.paramsSnapshot.variety?.displayName ?: "未标注品种",
            riskText = risks
        )
    }
    val legacy = messages.lastOrNull { it is ChatMessage.ResultReportMessage } as? ChatMessage.ResultReportMessage
    return legacy?.let {
        DashboardReportLike(
            score = it.score,
            level = it.level,
            varietyText = it.paramsSnapshot.variety?.displayName ?: "未标注品种",
            riskText = it.reportText.lineSequence().firstOrNull { line -> "风险" in line } ?: ""
        )
    }
}

private fun DurianParameters.dashboardCompleteFieldCount(): Int {
    return listOf(
        weightKg != null,
        variety != null,
        shape != null,
        largeLobes > 0 || smallLobes > 0
    ).count { it }
}

private fun dashboardIssueLabel(issue: String): String {
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

@Composable
fun FeatureCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "scale"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.9f)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp, pressedElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
