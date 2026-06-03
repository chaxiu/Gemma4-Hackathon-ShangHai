package com.winter.durianai.ui.screens.nativeui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.winter.durianai.R
import com.winter.durianai.data.local.prefs.UserPreferencesRepository
import com.winter.durianai.domain.model.DurianVariety
import com.winter.durianai.ui.screens.agent.AgentChatViewModel
import com.winter.durianai.ui.screens.agent.ChatSession
import com.winter.durianai.ui.screens.agent.models.ChatMessage
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    agentChatViewModel: AgentChatViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { UserPreferencesRepository(context) }
    val totalReports by prefs.reportTotalCount.collectAsState(initial = 0)
    val lowCount by prefs.reportLowCount.collectAsState(initial = 0)
    val streakDays by prefs.reportStreakDays.collectAsState(initial = 0)
    val level1Count by prefs.reportLevel1Count.collectAsState(initial = 0)
    val sessions by agentChatViewModel.sessions.collectAsState()
    val realReports = remember(sessions) { sessions.mapNotNull { it.latestBadgeReportLike() } }
    val standardPhotoSessions = remember(sessions) {
        sessions.count { session ->
            session.task.photos.qualityProfiles.size >= 5 &&
                session.task.photos.qualityProfiles.all { it.ok && !it.forcedUse }
        }
    }
    val angleErrorCount = remember(sessions) {
        sessions.sumOf { session ->
            session.task.photos.qualityProfiles.count { "wrong_angle" in it.issues }
        }
    }
    val lowYieldRealCount = realReports.count { it.level >= 4 }
    val level1RealCount = realReports.count { it.level == 1 }
    val coveredVarietyCount = remember(sessions) {
        sessions.mapNotNull { it.task.params.variety }
            .filter { it != DurianVariety.AUTO && it != DurianVariety.OTHER }
            .toSet()
            .size
    }
    val effectiveTotalReports = max(totalReports, realReports.size)
    val effectiveLowCount = max(lowCount, lowYieldRealCount)
    val effectiveLevel1Count = max(level1Count, level1RealCount)

    val badges = remember(
        effectiveTotalReports,
        effectiveLowCount,
        streakDays,
        effectiveLevel1Count,
        standardPhotoSessions,
        angleErrorCount,
        coveredVarietyCount
    ) {
        listOf(
            BadgeNode(
                name = "每日一鉴",
                imageRes = R.drawable.meiriyijian,
                isEarned = streakDays >= 3 || standardPhotoSessions >= 1,
                shortStatus = "完成一次标准五角度拍摄",
                detail = "五张照片都通过质检，说明你已经掌握朵然需要的基础取图方式。"
            ),
            BadgeNode(
                name = "排雷先锋",
                imageRes = R.drawable.paileixianfeng,
                isEarned = effectiveLowCount >= 1,
                shortStatus = "识别一次“低出肉率”",
                detail = "成功识别一颗出肉率偏低的榴莲，省下一顿心痛钱。"
            ),
            BadgeNode(
                name = "品质捕手",
                imageRes = R.drawable.pinzhibushou,
                isEarned = effectiveLevel1Count >= 5,
                shortStatus = "检查到 5 个 1 级品质",
                detail = "累计检查到 5 颗 1 级品质榴莲解锁。恭喜你，眼光越来越准。"
            ),
            BadgeNode(
                name = "图鉴达人",
                imageRes = R.drawable.tujiandaren,
                isEarned = coveredVarietyCount >= 3 || effectiveTotalReports >= 10,
                shortStatus = "覆盖 3 个品种或完成 10 次评测",
                detail = "用朵然记录不同品种的挑选经验，逐步形成自己的榴莲图鉴。"
            ),
            BadgeNode(
                name = "狂热果粉",
                imageRes = R.drawable.kuangreguofen,
                isEarned = angleErrorCount >= 1 || effectiveTotalReports >= 20,
                shortStatus = "识别一次角度错误或完成 20 次评测",
                detail = "能发现角度问题，说明你已经开始关注视觉分析所需的取图质量。"
            )
        )
    }
    var selectedBadge by remember { mutableStateOf<BadgeNode?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成就路线图", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "榴莲爱好者",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "已解锁 ${badges.count { it.isEarned }} / ${badges.size} 徽章",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Avatar",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            AchievementRoadmap(
                badges = badges,
                onBadgeClick = { selectedBadge = it }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (selectedBadge != null) {
        AlertDialog(
            onDismissRequest = { selectedBadge = null },
            confirmButton = {
                TextButton(onClick = { selectedBadge = null }) { Text("知道啦") }
            },
            title = { Text(selectedBadge!!.name, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Image(
                        painter = painterResource(id = selectedBadge!!.imageRes),
                        contentDescription = selectedBadge!!.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .align(Alignment.CenterHorizontally)
                            .alpha(if (selectedBadge!!.isEarned) 1f else 0.35f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (selectedBadge!!.isEarned) "已解锁" else "未解锁",
                        color = if (selectedBadge!!.isEarned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(selectedBadge!!.shortStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(selectedBadge!!.detail)
                }
            }
        )
    }
}

private data class BadgeNode(
    val name: String,
    val imageRes: Int,
    val isEarned: Boolean,
    val shortStatus: String,
    val detail: String
)

private data class BadgeReportLike(
    val score: Int,
    val level: Int
)

private fun ChatSession.latestBadgeReportLike(): BadgeReportLike? {
    task.analysis.latestReport?.let { return BadgeReportLike(score = it.score, level = it.level) }
    val legacyReport = messages.lastOrNull { it is ChatMessage.ResultReportMessage } as? ChatMessage.ResultReportMessage
    return legacyReport?.let { BadgeReportLike(score = it.score, level = it.level) }
}

@Composable
private fun AchievementRoadmap(
    badges: List<BadgeNode>,
    onBadgeClick: (BadgeNode) -> Unit
) {
    val rowHeight = 140.dp
    val primary = MaterialTheme.colorScheme.primary
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 16.dp)
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val startX = with(density) { 64.dp.toPx() }
        val endX = max(widthPx - with(density) { 64.dp.toPx() }, startX)
        val rowHeightPx = with(density) { rowHeight.toPx() }

        val strokeWidthPx = with(density) { 4.dp.toPx() }
        val nodeOuterRadiusPx = with(density) { 9.dp.toPx() }
        val nodeInnerRadiusPx = with(density) { 4.dp.toPx() }

        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight * badges.size)
        ) {
            if (badges.isNotEmpty()) {
                val points = badges.mapIndexed { index, _ ->
                    val x = if (index % 2 == 0) startX else endX
                    val y = rowHeightPx * index + rowHeightPx / 2f
                    x to y
                }

                val path = Path()
                path.moveTo(points.first().first, points.first().second)
                for (i in 1 until points.size) {
                    val (px, py) = points[i - 1]
                    val (nx, ny) = points[i]
                    val midY = (py + ny) / 2f
                    path.cubicTo(
                        px, midY,
                        nx, midY,
                        nx, ny
                    )
                }

                drawPath(
                    path = path,
                    color = primary.copy(alpha = 0.35f),
                    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                )

                points.forEachIndexed { index, (x, y) ->
                    val earned = badges[index].isEarned
                    drawCircle(
                        color = if (earned) primary else outlineVariant,
                        radius = nodeOuterRadiusPx,
                        center = androidx.compose.ui.geometry.Offset(x, y)
                    )
                    drawCircle(
                        color = surface,
                        radius = nodeInnerRadiusPx,
                        center = androidx.compose.ui.geometry.Offset(x, y)
                    )
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            badges.forEachIndexed { index, badge ->
                RoadmapRow(
                    badge = badge,
                    isLeft = index % 2 == 0,
                    rowHeight = rowHeight,
                    onClick = { onBadgeClick(badge) }
                )
            }
        }
    }
}

@Composable
private fun RoadmapRow(
    badge: BadgeNode,
    isLeft: Boolean,
    rowHeight: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLeft) {
            BadgeCard(badge = badge, onClick = onClick, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(56.dp))
            Spacer(modifier = Modifier.weight(1f))
        } else {
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(56.dp))
            BadgeCard(badge = badge, onClick = onClick, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun BadgeCard(
    badge: BadgeNode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alpha = if (badge.isEarned) 1f else 0.45f
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = badge.imageRes),
                    contentDescription = badge.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(alpha)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = badge.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().alpha(alpha)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = badge.shortStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().alpha(alpha)
            )
        }
    }
}
