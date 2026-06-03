package com.winter.durianai.ui.screens.agent.components.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winter.durianai.domain.model.DurianParameters
import com.winter.durianai.domain.model.DurianShape
import com.winter.durianai.domain.model.DurianVariety
import com.winter.durianai.ui.screens.agent.models.InputFormMode
import com.winter.durianai.ui.screens.agent.models.InputFormStatus

@Composable
fun InputFormWidget(
    params: DurianParameters,
    hasValidPhotos: Boolean,
    cameraEnabled: Boolean,
    mode: InputFormMode,
    status: InputFormStatus,
    onSubmit: (DurianParameters) -> Unit,
    onParamsChange: (DurianParameters) -> Unit,
    onCameraClick: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    var weightText by remember(params) { mutableStateOf(params.weightKg?.toString() ?: "") }
    var largeLobes by remember(params) { mutableIntStateOf(params.largeLobes) }
    var smallLobes by remember(params) { mutableIntStateOf(params.smallLobes) }
    var selectedShape by remember(params) { mutableStateOf(params.shape) }
    var selectedVariety by remember(params) { mutableStateOf(params.variety) }
    var varietyMenuExpanded by remember { mutableStateOf(false) }
    val enabled = mode == InputFormMode.Active
    fun currentDraftParams(): DurianParameters {
        return DurianParameters(
            weightKg = weightText.trim().toFloatOrNull(),
            largeLobes = largeLobes,
            smallLobes = smallLobes,
            shape = selectedShape,
            variety = selectedVariety
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth(0.9f)
            .padding(vertical = 8.dp)
            .then(if (mode == InputFormMode.History) Modifier.clickable { onExpand() } else Modifier)
            .alpha(1f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "榴莲物理参数",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = when (status) {
                    InputFormStatus.Pending -> "待分析"
                    InputFormStatus.Analyzing -> "分析中"
                    InputFormStatus.Done -> "分析完成"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (mode == InputFormMode.History) {
                Spacer(modifier = Modifier.height(10.dp))
                val summary = buildString {
                    val items = mutableListOf<String>()
                    params.variety?.displayName?.let { items.add("品种 $it") }
                    params.weightKg?.let { items.add("重量 ${it}kg") }
                    if (params.largeLobes > 0 || params.smallLobes > 0) items.add("房数 ${params.largeLobes}/${params.smallLobes}")
                    params.shape?.displayName?.let { items.add("形态 $it") }
                    append(if (items.isEmpty()) "已折叠" else items.joinToString(" · "))
                }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                TextButton(onClick = onExpand) { Text("展开") }
                return@Column
            }
            if (!hasValidPhotos) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("缺少五角度照片", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "拍齐后才能开始 AI 分析",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    TextButton(onClick = onCameraClick, enabled = cameraEnabled) {
                            Text("去拍摄")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "品种", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(
                        onClick = { varietyMenuExpanded = true },
                        enabled = enabled
                    ) {
                        Text(selectedVariety?.displayName ?: "选择品种")
                    }
                    DropdownMenu(
                        expanded = varietyMenuExpanded && enabled,
                        onDismissRequest = { varietyMenuExpanded = false }
                    ) {
                        DurianVariety.values().forEach { v ->
                            DropdownMenuItem(
                                text = { Text(v.displayName) },
                                onClick = {
                                    selectedVariety = v
                                    varietyMenuExpanded = false
                                    onParamsChange(currentDraftParams())
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Weight Input
            OutlinedTextField(
                value = weightText,
                onValueChange = {
                    if (enabled) {
                        weightText = it
                        onParamsChange(currentDraftParams())
                    }
                },
                label = { Text("重量 (kg)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Lobes Stepper
            Text(text = "房数预估", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("饱满大房", modifier = Modifier.weight(1f))
                Stepper(
                    value = largeLobes,
                    onValueChange = {
                        largeLobes = it
                        onParamsChange(currentDraftParams())
                    },
                    enabled = enabled
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("干瘪小房", modifier = Modifier.weight(1f))
                Stepper(
                    value = smallLobes,
                    onValueChange = {
                        smallLobes = it
                        onParamsChange(currentDraftParams())
                    },
                    enabled = enabled
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Shape Selector
            Text(text = "整体形态", style = MaterialTheme.typography.bodyMedium)
            val shapes = DurianShape.values()
            // For simplicity, a simple row of chips. In a real app, use a FlowRow.
            Column {
                Row(modifier = Modifier.fillMaxWidth()) {
                    ShapeChip(
                        shapes[0],
                        selectedShape,
                        { selectedShape = it; onParamsChange(currentDraftParams()) },
                        enabled,
                        Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ShapeChip(
                        shapes[1],
                        selectedShape,
                        { selectedShape = it; onParamsChange(currentDraftParams()) },
                        enabled,
                        Modifier.weight(1f)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    ShapeChip(
                        shapes[2],
                        selectedShape,
                        { selectedShape = it; onParamsChange(currentDraftParams()) },
                        enabled,
                        Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ShapeChip(
                        shapes[3],
                        selectedShape,
                        { selectedShape = it; onParamsChange(currentDraftParams()) },
                        enabled,
                        Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    onSubmit(currentDraftParams())
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && hasValidPhotos && weightText.toFloatOrNull() != null
            ) {
                Text("询问Doran")
            }
        }
    }
}

@Composable
private fun Stepper(value: Int, onValueChange: (Int) -> Unit, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { if (value > 0) onValueChange(value - 1) },
            enabled = enabled
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Decrease")
        }
        Text(text = value.toString(), style = MaterialTheme.typography.titleMedium)
        IconButton(
            onClick = { onValueChange(value + 1) },
            enabled = enabled
        ) {
            Icon(Icons.Default.Add, contentDescription = "Increase")
        }
    }
}

@Composable
private fun ShapeChip(
    shape: DurianShape,
    selectedShape: DurianShape?,
    onShapeSelected: (DurianShape) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = shape == selectedShape,
        onClick = { onShapeSelected(shape) },
        label = { Text(shape.displayName) },
        enabled = enabled,
        modifier = modifier
    )
}
