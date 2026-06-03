package com.winter.durianai.ui.screens.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winter.durianai.R
import com.winter.durianai.data.remote.llm.LlmCallLog
import com.winter.durianai.ui.AppLanguage
import com.winter.durianai.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appViewModel: AppViewModel,
    onNavigateBack: () -> Unit
) {
    val language by appViewModel.language.collectAsState()
    val devMode by appViewModel.devMode.collectAsState()
    val temperature by appViewModel.llmTemperature.collectAsState()
    val topP by appViewModel.llmTopP.collectAsState()
    val topK by appViewModel.llmTopK.collectAsState()
    val devLogs by appViewModel.devLogs.collectAsState()

    LaunchedEffect(devMode) {
        if (devMode) {
            appViewModel.refreshDevLogs()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.settings_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(24.dp)
        ) {
            Text(stringResource(id = R.string.settings_language), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    LanguageRow(
                        title = stringResource(id = R.string.language_system),
                        selected = language == AppLanguage.System,
                        onClick = { appViewModel.setLanguage(AppLanguage.System) }
                    )
                    LanguageRow(
                        title = stringResource(id = R.string.language_zh),
                        selected = language == AppLanguage.Zh,
                        onClick = { appViewModel.setLanguage(AppLanguage.Zh) }
                    )
                    LanguageRow(
                        title = stringResource(id = R.string.language_en),
                        selected = language == AppLanguage.En,
                        onClick = { appViewModel.setLanguage(AppLanguage.En) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
            Text(stringResource(id = R.string.settings_developer), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(id = R.string.dev_mode), fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                stringResource(id = R.string.dev_mode_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = devMode, onCheckedChange = { appViewModel.setDevMode(it) })
                    }

                    if (devMode) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(stringResource(id = R.string.model_params), fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AssistChip(
                                onClick = {
                                    appViewModel.setLlmTemperature(0.2)
                                    appViewModel.setLlmTopP(0.75)
                                    appViewModel.setLlmTopK(8)
                                },
                                label = { Text("稳定") }
                            )
                            AssistChip(
                                onClick = {
                                    appViewModel.setLlmTemperature(0.7)
                                    appViewModel.setLlmTopP(0.95)
                                    appViewModel.setLlmTopK(10)
                                },
                                label = { Text("灵活") }
                            )
                            AssistChip(
                                onClick = {
                                    appViewModel.setLlmTemperature(0.1)
                                    appViewModel.setLlmTopP(0.6)
                                    appViewModel.setLlmTopK(6)
                                },
                                label = { Text("严格工具") }
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        DevSlider(
                            label = stringResource(id = R.string.param_temperature),
                            valueText = String.format("%.2f", temperature),
                            value = temperature.toFloat(),
                            valueRange = 0.0f..2.0f,
                            steps = 39,
                            onValueChange = { appViewModel.setLlmTemperature(it.toDouble()) }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        DevSlider(
                            label = stringResource(id = R.string.param_top_p),
                            valueText = String.format("%.2f", topP),
                            value = topP.toFloat(),
                            valueRange = 0.0f..1.0f,
                            steps = 99,
                            onValueChange = { appViewModel.setLlmTopP(it.toDouble()) }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        DevSlider(
                            label = stringResource(id = R.string.param_top_k),
                            valueText = topK.toString(),
                            value = topK.toFloat(),
                            valueRange = 1f..100f,
                            steps = 98,
                            onValueChange = { appViewModel.setLlmTopK(it.toInt().coerceIn(1, 100)) }
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(stringResource(id = R.string.model_logs), fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { appViewModel.clearDevLogs() }) { Text(stringResource(id = R.string.action_clear)) }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { appViewModel.refreshDevLogs() }) { Text(stringResource(id = R.string.action_refresh)) }
                        }

                        if (devLogs.isEmpty()) {
                            Text(
                                stringResource(id = R.string.no_logs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            devLogs.asReversed().take(12).forEach { log ->
                                Spacer(modifier = Modifier.height(10.dp))
                                DevLogRow(log = log)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DevSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(valueText, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DevLogRow(log: LlmCallLog) {
    val title = buildString {
        append(log.kind)
        append(" · ")
        append(if (log.success) "OK" else "ERR")
        append(" · ")
        append(log.durationMs).append("ms")
    }
    val detail = buildString {
        if (!log.modelPath.isNullOrBlank()) {
            append("model=").append(log.modelPath)
        }
        if (log.kind == "chat") {
            if (isNotEmpty()) append("\n")
            append("temp=").append(log.temperature)
                .append(" topP=").append(log.topP)
                .append(" topK=").append(log.topK)
            append("\n")
            append("inputChars=").append(log.inputChars)
            if (log.outputChars != null) append(" outputChars=").append(log.outputChars)
        }
        if (!log.responsePreview.isNullOrBlank()) {
            if (isNotEmpty()) append("\n")
            append(log.responsePreview)
        }
        if (!log.error.isNullOrBlank()) {
            append("\n").append(log.error)
        }
    }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (log.success) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LanguageRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        RadioButton(selected = selected, onClick = onClick)
    }
}
