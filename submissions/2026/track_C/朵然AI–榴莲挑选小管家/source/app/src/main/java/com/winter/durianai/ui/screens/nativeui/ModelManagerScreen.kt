package com.winter.durianai.ui.screens.nativeui

import android.content.Context
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winter.durianai.R
import com.winter.durianai.data.local.prefs.UserPreferencesRepository
import com.winter.durianai.ui.components.DoranLoadingIndicator
import com.winter.durianai.ui.screens.agent.AgentChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    agentChatViewModel: AgentChatViewModel,
    onNavigateBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { UserPreferencesRepository(context) }
    val devModeEnabled by prefs.devMode.collectAsState(initial = true)
    val activeModelPathPref by prefs.activeModelPath.collectAsState(initial = null)
    val lastDetectHasModel by prefs.modelLastDetectHasModel.collectAsState(initial = false)
    val preferredBackend by agentChatViewModel.preferredBackend.collectAsState()
    val engineBackendUsed by agentChatViewModel.engineBackendUsed.collectAsState()
    val gpuCapability by agentChatViewModel.gpuCapability.collectAsState()

    var isWorking by remember { mutableStateOf(false) }
    var isDetecting by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var backendHintText by remember { mutableStateOf<String?>(null) }
    var isSwitchingBackend by remember { mutableStateOf(false) }
    var isHealthChecking by remember { mutableStateOf(false) }
    var healthCheckText by remember { mutableStateOf<String?>(null) }

    var modelsRefreshKey by remember { mutableStateOf(0) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlDraft by remember { mutableStateOf("") }
    var expandedPath by remember { mutableStateOf<String?>(null) }
    var pendingDeletePath by remember { mutableStateOf<String?>(null) }

    val externalBase = remember { context.getExternalFilesDir(null) }
    val modelsDir = remember(externalBase) {
        externalBase?.let { File(it, "models").apply { mkdirs() } }
    }

    fun refreshInfo() {
        scope.launch {
            val startMs = System.currentTimeMillis()
            isDetecting = true
            try {
                errorText = null
                val hasAny = withContext(Dispatchers.IO) { listLocalModelFiles(context).isNotEmpty() }
                prefs.setModelLastDetectHasModel(hasAny)
                modelsRefreshKey++
            } catch (e: Exception) {
                errorText = e.localizedMessage ?: "检测失败"
            } finally {
                val elapsed = System.currentTimeMillis() - startMs
                val minShowMs = 450L
                if (elapsed < minShowMs) delay(minShowMs - elapsed)
                isDetecting = false
            }
        }
    }

    LaunchedEffect(lastDetectHasModel, activeModelPathPref) {
        if (!lastDetectHasModel) refreshInfo()
    }

    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isWorking = true
            errorText = null
            progressText = "正在导入…"
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            try {
                val importedPath = withContext(Dispatchers.IO) {
                    val dir = modelsDir ?: throw IllegalStateException("外部存储不可用")
                    val displayName = queryDisplayName(context.contentResolver, uri)
                    val baseName = sanitizeModelFileName(displayName ?: "doran_${System.currentTimeMillis()}.litertlm")

                    var dest = File(dir, baseName)
                    var idx = 2
                    while (dest.exists()) {
                        dest = File(dir, "${dest.nameWithoutExtension}_$idx.${dest.extension}")
                        idx++
                    }

                    copyUriToFile(context.contentResolver, uri, dest) { copied, total ->
                        progressText = if (total > 0) {
                            val pct = (copied * 100 / total).toInt()
                            "正在导入… $pct%"
                        } else {
                            "正在导入…"
                        }
                    }
                    dest.absolutePath
                }
                prefs.setActiveModelPath(importedPath)
                modelsRefreshKey++
                refreshInfo()
                agentChatViewModel.invalidateEngine()
                progressText = "导入完成（回到对话页会自动载入）"
            } catch (e: Exception) {
                errorText = e.localizedMessage ?: "导入失败"
            } finally {
                isWorking = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.model_manager_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.cd_back))
                    }
                },
                actions = {
                    if (isDetecting) {
                        DoranLoadingIndicator(indicatorSize = 16.dp)
                    } else {
                        IconButton(onClick = { refreshInfo() }, enabled = !isWorking) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(id = R.string.model_manager_redetect))
                        }
                    }
                    IconButton(
                        onClick = { localPicker.launch(arrayOf("*/*")) },
                        enabled = externalBase != null && !isWorking
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = "本地导入")
                    }
                    IconButton(
                        onClick = { urlDraft = ""; showUrlDialog = true },
                        enabled = externalBase != null && !isWorking
                    ) {
                        Icon(Icons.Default.Link, contentDescription = "URL 下载")
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
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val modelFiles = remember(modelsRefreshKey, externalBase) {
                listLocalModelFiles(context).sortedByDescending { it.lastModified() }
            }

            LaunchedEffect(modelFiles, activeModelPathPref, externalBase) {
                val external = externalBase ?: return@LaunchedEffect
                if (modelFiles.isEmpty()) return@LaunchedEffect
                val active = activeModelPathPref
                val activeOk = active != null &&
                    active.startsWith(external.absolutePath) &&
                    File(active).exists() &&
                    modelFiles.any { it.absolutePath == active }
                if (!activeOk) {
                    prefs.setActiveModelPath(modelFiles.first().absolutePath)
                    agentChatViewModel.invalidateEngine()
                    modelsRefreshKey++
                }
            }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (isDetecting) {
                            DoranLoadingIndicator(indicatorSize = 16.dp)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    if (modelFiles.isEmpty()) {
                        val hint = if (externalBase == null) "外部存储不可用" else "暂无可用模型，请在下方导入或下载。"
                        Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        modelFiles.forEach { file ->
                            val isActive = file.absolutePath == activeModelPathPref
                            val compatibility = modelCompatibility(file)
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = file.name,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { expandedPath = if (expandedPath == file.absolutePath) null else file.absolutePath }
                                    ) {
                                        Icon(
                                            imageVector = if (expandedPath == file.absolutePath) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null
                                        )
                                    }
                                }
                                if (isActive) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "当前默认 · ${compatibility.status}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (compatibility.isLikelyCompatible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .background(
                                                color = if (compatibility.isLikelyCompatible) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                                                shape = RoundedCornerShape(999.dp)
                                            )
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    if (!isActive) {
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    prefs.setActiveModelPath(file.absolutePath)
                                                    modelsRefreshKey++
                                                    refreshInfo()
                                                    agentChatViewModel.invalidateEngine()
                                                }
                                            },
                                            enabled = externalBase != null && !isWorking && !isDetecting
                                        ) { Text("使用") }
                                    }
                                    TextButton(
                                        onClick = { pendingDeletePath = file.absolutePath },
                                        enabled = externalBase != null && !isWorking && !isDetecting
                                    ) { Text("删除") }
                                }
                                if (expandedPath == file.absolutePath) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "大小：${formatBytes(file.length())}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "兼容性：${compatibility.detail}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (compatibility.isLikelyCompatible) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "视觉能力：${compatibility.visionHint}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (devModeEnabled) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "路径：${file.absolutePath}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    isHealthChecking = true
                                    healthCheckText = null
                                    errorText = null
                                    try {
                                        healthCheckText = agentChatViewModel.runModelHealthCheck()
                                    } finally {
                                        isHealthChecking = false
                                        modelsRefreshKey++
                                    }
                                }
                            },
                            enabled = !isWorking && !isDetecting && !isHealthChecking && activeModelPathPref != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isHealthChecking) {
                                DoranLoadingIndicator(indicatorSize = 16.dp)
                                Spacer(modifier = Modifier.size(8.dp))
                            }
                            Text(if (isHealthChecking) "检查中…" else "健康检查")
                        }
                        if (healthCheckText != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = healthCheckText!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (healthCheckText!!.contains("通过")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            if (devModeEnabled) {
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("推理后端", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))

                        val gpuEnabled = gpuCapability?.openClRuntimeAvailable == true && !isSwitchingBackend
                        val cpuEnabled = !isSwitchingBackend

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = preferredBackend.lowercase() != "gpu",
                                onClick = {
                                    scope.launch {
                                        isSwitchingBackend = true
                                        prefs.setLlmBackend("cpu")
                                        agentChatViewModel.invalidateEngine()
                                        backendHintText = "已设置为 CPU（回到对话页会重新载入生效）"
                                        isSwitchingBackend = false
                                    }
                                },
                                enabled = cpuEnabled
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("CPU", fontWeight = FontWeight.Bold)
                                Text(
                                    "兼容性最好（推荐）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (preferredBackend.lowercase() != "gpu" && isSwitchingBackend) {
                                DoranLoadingIndicator(indicatorSize = 18.dp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = preferredBackend.lowercase() == "gpu",
                                onClick = {
                                    scope.launch {
                                        isSwitchingBackend = true
                                        prefs.setLlmBackend("gpu")
                                        agentChatViewModel.invalidateEngine()
                                        backendHintText = "已设置为 GPU（回到对话页会重新载入生效）"
                                        isSwitchingBackend = false
                                    }
                                },
                                enabled = gpuEnabled
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "GPU",
                                    fontWeight = FontWeight.Bold,
                                    color = if (gpuEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    if (gpuCapability?.openClRuntimeAvailable == true) "需要 OpenCL 运行时" else "当前设备未检测到 OpenCL（模拟器常见）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (preferredBackend.lowercase() == "gpu" && isSwitchingBackend) {
                                DoranLoadingIndicator(indicatorSize = 18.dp)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "当前实际：${if (engineBackendUsed == "gpu") "GPU" else "CPU"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val cap = gpuCapability
                        if (cap != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "诊断：OpenCL=${if (cap.openClRuntimeAvailable) "可用" else "不可用"}；" +
                                    "TopK OpenCL 插件=${if (cap.openClSamplerPluginPresent) "已打包" else "未打包"}；" +
                                    "TopK WebGPU 插件=${if (cap.webGpuSamplerPluginPresent) "已打包" else "未打包"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (backendHintText != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = backendHintText!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (isWorking) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DoranLoadingIndicator(indicatorSize = 18.dp)
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(progressText ?: "处理中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (progressText != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(progressText!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (errorText != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(errorText!!, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { if (!isWorking) showUrlDialog = false },
            title = { Text("URL 下载") },
            text = {
                Column {
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        label = { Text("模型 URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isWorking
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isWorking = true
                            errorText = null
                            progressText = "开始下载…"
                            try {
                                val downloadedPath = withContext(Dispatchers.IO) {
                                    val dir = modelsDir ?: throw IllegalStateException("外部存储不可用")
                                    val urlTrimmed = urlDraft.trim()
                                    val last = urlTrimmed.substringAfterLast('/', missingDelimiterValue = "")
                                    val baseName = sanitizeModelFileName(
                                        if (last.isBlank()) "doran_${System.currentTimeMillis()}.litertlm" else last
                                    )

                                    var dest = File(dir, baseName)
                                    var idx = 2
                                    while (dest.exists()) {
                                        dest = File(dir, "${dest.nameWithoutExtension}_$idx.${dest.extension}")
                                        idx++
                                    }

                                    downloadToFile(
                                        url = urlTrimmed,
                                        dest = dest,
                                        onProgress = { copied, total ->
                                            progressText = if (total > 0) {
                                                val pct = (copied * 100 / total).toInt()
                                                "正在下载… $pct%"
                                            } else {
                                                "正在下载…"
                                            }
                                        }
                                    )
                                    dest.absolutePath
                                }
                                prefs.setActiveModelPath(downloadedPath)
                                modelsRefreshKey++
                                refreshInfo()
                                agentChatViewModel.invalidateEngine()
                                progressText = "下载完成（回到对话页会自动载入）"
                                showUrlDialog = false
                            } catch (e: Exception) {
                                errorText = e.localizedMessage ?: "下载失败"
                            } finally {
                                isWorking = false
                            }
                        }
                    },
                    enabled = !isWorking && urlDraft.trim().isNotBlank()
                ) { Text("下载") }
            },
            dismissButton = {
                TextButton(onClick = { if (!isWorking) showUrlDialog = false }, enabled = !isWorking) {
                    Text("取消")
                }
            }
        )
    }

    val deletePath = pendingDeletePath
    if (deletePath != null) {
        val file = remember(deletePath) { File(deletePath) }
        AlertDialog(
            onDismissRequest = { if (!isWorking) pendingDeletePath = null },
            title = { Text("确认删除？") },
            text = { Text(file.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isWorking = true
                            try {
                                withContext(Dispatchers.IO) { file.delete() }
                                if (activeModelPathPref == deletePath) {
                                    prefs.setActiveModelPath(null)
                                    agentChatViewModel.invalidateEngine()
                                }
                                modelsRefreshKey++
                                refreshInfo()
                            } finally {
                                isWorking = false
                                pendingDeletePath = null
                            }
                        }
                    },
                    enabled = !isWorking
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { if (!isWorking) pendingDeletePath = null }, enabled = !isWorking) {
                    Text("取消")
                }
            }
        )
    }
}

private fun listLocalModelFiles(context: Context): List<File> {
    val external = context.getExternalFilesDir(null) ?: return emptyList()
    val out = LinkedHashMap<String, File>()

    fun addFromDir(dir: File?) {
        if (dir == null || !dir.exists()) return
        dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("litertlm", ignoreCase = true) }
            ?.forEach { out[it.absolutePath] = it }
    }

    addFromDir(File(external, "models"))
    addFromDir(external)

    return out.values.toList()
}

private fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
    return runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx < 0) return@use null
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(idx)
        }
    }.getOrNull()
}

private fun sanitizeModelFileName(value: String): String {
    val trimmed = value.trim().ifBlank { "doran_${System.currentTimeMillis()}.litertlm" }
    val withExt = if (trimmed.lowercase().endsWith(".litertlm")) trimmed else "$trimmed.litertlm"
    return withExt.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

private data class ModelCompatibility(
    val status: String,
    val detail: String,
    val visionHint: String,
    val isLikelyCompatible: Boolean
)

private fun modelCompatibility(file: File): ModelCompatibility {
    val extOk = file.extension.equals("litertlm", ignoreCase = true)
    val sizeMb = file.length() / (1024.0 * 1024.0)
    val sizeOk = sizeMb >= 50.0
    return when {
        !extOk -> ModelCompatibility(
            status = "后缀异常",
            detail = "需要 .litertlm 文件，当前后缀可能无法被 LiteRT-LM 加载。",
            visionHint = "无法判断视觉能力。",
            isLikelyCompatible = false
        )
        !sizeOk -> ModelCompatibility(
            status = "需确认",
            detail = "文件小于 50 MB，可能不是完整模型或只是元数据文件。",
            visionHint = "仅凭文件名无法判断视觉能力，建议做健康检查或实际试跑。",
            isLikelyCompatible = false
        )
        else -> ModelCompatibility(
            status = "格式可用",
            detail = "后缀和体积看起来符合 LiteRT-LM 本地模型的基本要求。",
            visionHint = "视觉能力不再按文件名判断，建议做健康检查或实际试跑确认。",
            isLikelyCompatible = true
        )
    }
}

private fun copyUriToFile(
    contentResolver: ContentResolver,
    uri: Uri,
    dest: File,
    onProgress: (copied: Long, total: Long) -> Unit
) {
    val total = try {
        contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
    } catch (_: Exception) {
        -1L
    }

    contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(dest).use { output ->
            val buffer = ByteArray(1024 * 256)
            var copied = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                copied += read
                onProgress(copied, total)
            }
            output.flush()
        }
    } ?: throw IllegalStateException("无法读取文件")
}

private fun downloadToFile(
    url: String,
    dest: File,
    onProgress: (copied: Long, total: Long) -> Unit
) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        throw IllegalArgumentException("URL 必须以 http:// 或 https:// 开头")
    }

    val client = OkHttpClient()
    val request = Request.Builder().url(url).build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("下载失败：${response.code}")
        val body = response.body ?: throw IllegalStateException("空响应")
        val total = body.contentLength()
        body.byteStream().use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(1024 * 256)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress(copied, total)
                }
                output.flush()
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.2f MB", bytes / mb)
        bytes >= kb -> String.format("%.2f KB", bytes / kb)
        else -> "$bytes B"
    }
}
