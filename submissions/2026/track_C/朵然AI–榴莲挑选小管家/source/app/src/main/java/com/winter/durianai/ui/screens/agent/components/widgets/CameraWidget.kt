package com.winter.durianai.ui.screens.agent.components.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CameraWidget(
    onCameraClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth(0.85f)
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val iconBg = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
            val iconTint = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Camera",
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = iconBg,
                        shape = RoundedCornerShape(50)
                    )
                    .padding(12.dp),
                tint = iconTint
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "开启视觉识别",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (enabled) "需要拍摄 5 个视角的照片" else "本地模型未就绪，暂时无法拍摄",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onCameraClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled
            ) {
                Text(if (enabled) "立即拍摄" else "等待模型加载")
            }
        }
    }
}
