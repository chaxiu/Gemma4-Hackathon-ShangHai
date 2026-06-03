package com.winter.durianai.ui.screens.agent.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.winter.durianai.ui.theme.PrimaryGreen
import com.winter.durianai.ui.theme.PrimaryGreenLight

@Composable
fun MessageBubble(text: String, isFromUser: Boolean) {
    var isVisible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        isVisible = true
    }
    
    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.8f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "bubble_scale"
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "bubble_alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .alpha(alpha)
            .scale(scale),
        horizontalArrangement = if (isFromUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = if (isFromUser) 20.dp else 4.dp,
                        bottomEnd = if (isFromUser) 4.dp else 20.dp
                    )
                )
                .background(
                    if (isFromUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = remember(text) { parseBoldMarkdown(text) },
                color = if (isFromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

private fun parseBoldMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < text.length) {
            val start = text.indexOf("**", startIndex = cursor)
            if (start < 0) {
                append(text.substring(cursor))
                break
            }

            val end = text.indexOf("**", startIndex = start + 2)
            if (end < 0) {
                append(text.substring(cursor))
                break
            }

            append(text.substring(cursor, start))
            val boldText = text.substring(start + 2, end).trim()
            if (boldText.isNotEmpty()) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(boldText)
                }
            }
            cursor = end + 2
        }
    }
}
