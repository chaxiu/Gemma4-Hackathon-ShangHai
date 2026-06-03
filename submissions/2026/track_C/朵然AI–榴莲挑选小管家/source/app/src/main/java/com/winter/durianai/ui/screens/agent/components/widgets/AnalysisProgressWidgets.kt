package com.winter.durianai.ui.screens.agent.components.widgets

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winter.durianai.ui.screens.agent.models.AnalysisStepStatus
import com.winter.durianai.ui.screens.agent.models.ChatMessage
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun AnalysisProgressCompactCard(
    message: ChatMessage.AnalysisProgressMessage,
    onOpenDetails: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val doneCount = remember(message.steps) { message.steps.count { it.status == AnalysisStepStatus.Done } }
    val totalCount = remember(message.steps) { message.steps.size.coerceAtLeast(1) }
    val percentText = remember(message.overallProgress) {
        "${(message.overallProgress.coerceIn(0f, 1f) * 100).roundToInt()}%"
    }

    Card(
        modifier = modifier
            .fillMaxWidth(0.9f)
            .padding(vertical = 8.dp)
            .clickable { onOpenDetails() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = message.title,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = message.currentStepTitle ?: if (message.canCancel) "准备中…" else "已完成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                if (message.canCancel) {
                    TextButton(onClick = onCancel) { Text("取消") }
                }
                IconButton(onClick = onOpenDetails) {
                    Icon(Icons.Default.ExpandMore, contentDescription = "展开", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            WaveProgressBar(
                progress = message.overallProgress.coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "进度 $doneCount/$totalCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = percentText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun AnalysisProgressDetailsSheet(
    message: ChatMessage.AnalysisProgressMessage,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(top = 6.dp, bottom = 10.dp)) {
        Text(message.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))
        WaveProgressBar(
            progress = message.overallProgress.coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message.currentStepTitle ?: if (message.canCancel) "准备中…" else "已完成",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("步骤", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            message.steps.forEach { step ->
                val statusText = when (step.status) {
                    AnalysisStepStatus.Pending -> "等待"
                    AnalysisStepStatus.Running -> "进行中"
                    AnalysisStepStatus.Done -> "完成"
                    AnalysisStepStatus.Cancelled -> "已取消"
                    AnalysisStepStatus.Failed -> "失败"
                }
                val icon = when (step.status) {
                    AnalysisStepStatus.Done -> Icons.Default.CheckCircle
                    AnalysisStepStatus.Running -> Icons.Default.Refresh
                    AnalysisStepStatus.Cancelled -> Icons.Default.History
                    AnalysisStepStatus.Failed -> Icons.Default.Info
                    AnalysisStepStatus.Pending -> Icons.Default.History
                }
                val tint = when (step.status) {
                    AnalysisStepStatus.Done -> MaterialTheme.colorScheme.primary
                    AnalysisStepStatus.Running -> MaterialTheme.colorScheme.primary
                    AnalysisStepStatus.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.width(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(step.title, fontWeight = FontWeight.SemiBold)
                        step.detail?.let {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text("临时结果", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        if (message.interim.isEmpty()) {
            Text("暂无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    message.interim.forEach { (k, v) ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = k,
                                modifier = Modifier.widthIn(min = 84.dp, max = 120.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = v,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (message.canCancel) {
                TextButton(onClick = onCancel) { Text("取消分析") }
            }
        }
    }
}

@Composable
private fun WaveProgressBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val p = progress.coerceIn(0f, 1f)
    val infinite = rememberInfiniteTransition(label = "wave")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val waveColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surface
    val highlightColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val r = minOf(h / 2f, 999f)
        val clip = Path().apply {
            addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(r, r)))
        }

        clipPath(clip) {
            drawRect(color = trackColor)

            val fillW = w * p
            if (fillW <= 0.5f) return@clipPath

            val amplitude = minOf(h * 0.30f, 5f)
            val wavelength = maxOf(18f, h * 3.5f)
            val k = (2f * PI).toFloat() / wavelength
            val baseline = h * 0.55f

            clipRect(left = 0f, top = 0f, right = fillW, bottom = h) {
                val fill = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, baseline)
                    var x = 0f
                    while (x <= fillW) {
                        val y = baseline + (sin((phase + x * k).toDouble()) * amplitude).toFloat()
                        lineTo(x, y)
                        x += 2f
                    }
                    lineTo(fillW, h)
                    close()
                }
                drawPath(fill, color = waveColor)

                val crest = Path().apply {
                    var x = 0f
                    moveTo(0f, baseline + (sin(phase.toDouble()) * amplitude).toFloat())
                    while (x <= fillW) {
                        val y = baseline + (sin((phase + x * k).toDouble()) * amplitude).toFloat()
                        lineTo(x, y)
                        x += 2f
                    }
                }
                drawPath(crest, color = highlightColor)
            }
        }
    }
}
