package com.winter.durianai.ui.screens.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import com.winter.durianai.R
import com.winter.durianai.domain.model.AnalysisTaskStatus
import com.winter.durianai.domain.model.DurianParameters
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winter.durianai.domain.model.DurianVariety
import com.winter.durianai.domain.model.PhotoSetStatus
import com.winter.durianai.data.local.prefs.UserPreferencesRepository
import com.winter.durianai.ui.screens.agent.components.ChatInputBar
import com.winter.durianai.ui.screens.agent.components.MessageBubble
import com.winter.durianai.ui.screens.agent.components.widgets.AnalysisProgressCompactCard
import com.winter.durianai.ui.screens.agent.components.widgets.AnalysisProgressDetailsSheet
import com.winter.durianai.ui.screens.agent.components.widgets.CameraWidget
import com.winter.durianai.ui.screens.agent.components.widgets.InputFormWidget
import com.winter.durianai.ui.screens.agent.models.ChatMessage
import com.winter.durianai.ui.screens.agent.models.InputFormStatus

import com.winter.durianai.ui.components.DoranLoadingIndicator
import com.winter.durianai.ui.components.DurianEmoji
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
    viewModel: AgentChatViewModel = viewModel(),
    onNavigateToProfile: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToModelManager: () -> Unit = {},
    onNavigateToCamera: () -> Unit = {},
    appViewModel: com.winter.durianai.ui.AppViewModel? = null,
    onOpenDrawer: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { UserPreferencesRepository(context) }
    val activeModelPath by prefs.activeModelPath.collectAsState(initial = null)
    val devModeEnabled by prefs.devMode.collectAsState(initial = true)

    val messages by viewModel.messages.collectAsState()
    val visibleMessages = remember(messages, devModeEnabled) {
        messages.filterNot { message ->
            !devModeEnabled && message is ChatMessage.ToolCallMessage
        }
    }
    val listState = rememberLazyListState()
    val isModelLoading by viewModel.isModelLoading.collectAsState()
    val isEngineReady by viewModel.isEngineReady.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()
    val isReportAnalyzing by viewModel.isReportAnalyzing.collectAsState()
    val analysisStage by viewModel.analysisStage.collectAsState()
    val currentParams by viewModel.currentParamsFlow.collectAsState()
    val currentTask by viewModel.currentTaskState.collectAsState()
    val visionCaptureEvent by viewModel.visionCaptureEvent.collectAsState()
    val inputLocked = isModelLoading || !isEngineReady || isReportAnalyzing
    val isPhotoInvalid = currentTask.photos.status == PhotoSetStatus.Invalid
    val hasValidPhotos = currentTask.photos.status == PhotoSetStatus.Ready
    val lastReportId = remember(messages) {
        (messages.asReversed().firstOrNull { it is ChatMessage.ResultReportMessage } as? ChatMessage.ResultReportMessage)?.id
    }
    var showSummarySheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    val sessions by viewModel.sessions.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    var showSessionPickerSheet by remember { mutableStateOf(false) }
    var showSessionManageSheet by remember { mutableStateOf(false) }
    var sessionTitleDraft by remember { mutableStateOf("") }
    var showVarietySheet by remember { mutableStateOf(false) }
    var showWeightDialog by remember { mutableStateOf(false) }
    var showShapeSheet by remember { mutableStateOf(false) }
    var showLobesDialog by remember { mutableStateOf(false) }
    var weightDraft by remember { mutableStateOf("") }
    var largeLobesQuick by remember { mutableStateOf(0) }
    var smallLobesQuick by remember { mutableStateOf(0) }
    var showFormDetailsSheet by remember { mutableStateOf(false) }
    var formDetailsParams by remember { mutableStateOf(DurianParameters()) }
    var formDetailsStatus by remember { mutableStateOf(InputFormStatus.Pending) }
    var analysisDetailsMessageId by remember { mutableStateOf<String?>(null) }
    var imagePreviewResId by remember { mutableStateOf<Int?>(null) }
    var imagePreviewPath by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val chatCameraCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        val saved = bitmap?.let { saveBitmapToChatImage(context, it) }
        if (saved != null) {
            viewModel.onChatImageSelected(saved.absolutePath, "聊天拍照")
        }
    }
    val chatCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            chatCameraCapture.launch(null)
        }
    }
    val chatAlbumPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val saved = uri?.let { copyChatImageToCache(context, it) }
        if (saved != null) {
            viewModel.onChatImageSelected(saved.absolutePath, "本地图片")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.ensureInitialCopyVisible()
        viewModel.maybeInitEngine(reason = "进入对话页")
    }

    LaunchedEffect(Unit) {
        viewModel.taskEvents.collect { event ->
            event.userMessage?.let { message ->
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(visibleMessages.size) {
        if (visibleMessages.isNotEmpty()) {
            listState.animateScrollToItem(visibleMessages.size - 1)
        }
    }

    LaunchedEffect(visionCaptureEvent) {
        if (visionCaptureEvent != 0L) {
            onNavigateToCamera()
            viewModel.consumeVisionCaptureEvent()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                title = { 
                    val currentSession = sessions.find { it.id == currentSessionId }
                    val modelText = if (isModelLoading) "模型加载中…" else if (isEngineReady) {
                        activeModelPath?.let { File(it).nameWithoutExtension } ?: "Doran"
                    } else {
                        "模型未准备"
                    }
                    val modelColor = if (isEngineReady) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showSessionPickerSheet = true }
                        ) {
                            Text(
                                text = currentSession?.title ?: "朵然 AI",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Row(
                                modifier = Modifier.clickable(enabled = !isModelLoading) { showModelSheet = true },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = modelText,
                                    color = modelColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 180.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "切换模型",
                                    modifier = Modifier.size(14.dp),
                                    tint = modelColor
                                )
                            }
                        }
                        IconButton(
                            onClick = { showSessionPickerSheet = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.SwapHoriz,
                                contentDescription = "切换任务",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.createNewSession() },
                            enabled = !isModelLoading,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "New Session",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                actions = {
                    if (isModelLoading) {
                        DoranLoadingIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            indicatorSize = 16.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        IconButton(onClick = { viewModel.refreshEngine() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Model")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
            if (isModelLoading) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DoranLoadingIndicator(
                            indicatorSize = 16.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("本地模型加载中…", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "加载完成后会自动启用本机推理",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        TextButton(onClick = { viewModel.cancelEngineInit() }) {
                            Text("终止")
                        }
                    }
                }
            }
            if (!isEngineReady && !isModelLoading) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("本地模型未准备", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "去模型管理导入（本地选择 / URL 下载）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = onNavigateToModelManager) { Text("打开") }
                    }
                }
            }

            // Chat Area
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(visibleMessages, key = { it.id }) { message ->
                    when (message) {
                        is ChatMessage.TextMessage -> {
                            MessageBubble(text = message.text, isFromUser = message.isFromUser)
                        }
                        is ChatMessage.ImageStripMessage -> {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .align(if (message.isFromUser) Alignment.CenterEnd else Alignment.CenterStart)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (message.isFromUser) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (message.imagePaths.isNotEmpty()) {
                                        message.imagePaths.take(6).forEach { path ->
                                            Box {
                                                AsyncImage(
                                                    model = File(path),
                                                    contentDescription = message.label,
                                                    modifier = Modifier
                                                        .size(56.dp)
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .clickable { imagePreviewPath = path },
                                                    contentScale = ContentScale.Crop
                                                )
                                                if (message.showDoranCheckAction) {
                                                    AssistChip(
                                                        onClick = {
                                                            viewModel.prepareVisionCheckFromChatImage(path)
                                                            onNavigateToCamera()
                                                        },
                                                        label = { Text("Doran检查") },
                                                        colors = AssistChipDefaults.assistChipColors(
                                                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                                                        ),
                                                        modifier = Modifier
                                                            .align(Alignment.BottomEnd)
                                                            .padding(4.dp)
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        message.imageResIds.take(6).forEach { resId ->
                                            Image(
                                                painter = painterResource(id = resId),
                                                contentDescription = message.label,
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable { imagePreviewResId = resId },
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                    if (!message.label.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = message.label,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        is ChatMessage.AudioMessage -> {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                AudioMessageBubble(
                                    durationMs = message.durationMs,
                                    prompt = message.prompt,
                                    isFromUser = message.isFromUser,
                                    modifier = Modifier.align(if (message.isFromUser) Alignment.CenterEnd else Alignment.CenterStart)
                                )
                            }
                        }
                        is ChatMessage.CameraWidgetMessage -> {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                CameraWidget(
                                    onCameraClick = { onNavigateToCamera() },
                                    enabled = !inputLocked,
                                    modifier = Modifier.align(Alignment.CenterStart)
                                )
                            }
                        }
                        is ChatMessage.InputFormWidgetMessage -> {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                InputFormWidget(
                                    params = message.params,
                                    hasValidPhotos = hasValidPhotos,
                                    cameraEnabled = !inputLocked,
                                    mode = message.mode,
                                    status = message.status,
                                    onSubmit = { params -> viewModel.onParametersSubmitted(message.id, params) },
                                    onParamsChange = { params -> viewModel.onParametersDraftChanged(params) },
                                    onCameraClick = { onNavigateToCamera() },
                                    onExpand = {
                                        formDetailsParams = message.params
                                        formDetailsStatus = message.status
                                        showFormDetailsSheet = true
                                    },
                                    modifier = Modifier.align(Alignment.CenterStart)
                                )
                            }
                        }
                        is ChatMessage.AnalysisProgressMessage -> {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                AnalysisProgressCompactCard(
                                    message = message,
                                    onOpenDetails = { analysisDetailsMessageId = message.id },
                                    onCancel = { viewModel.cancelPhotoDrivenAnalysis() },
                                    modifier = Modifier.align(Alignment.CenterStart)
                                )
                            }
                        }
                        is ChatMessage.ResultReportMessage -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                ResultReportCard(
                                    paramsSnapshot = message.paramsSnapshot,
                                    imageResIds = message.imageResIds,
                                    imagePaths = message.imagePaths,
                                    score = message.score,
                                    level = message.level,
                                    reportText = message.reportText,
                                    onImageResClick = { imagePreviewResId = it },
                                    onImagePathClick = { imagePreviewPath = it }
                                )

                                if (message.id == lastReportId) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = { viewModel.createNewSession() },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("开始新的会话")
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.resetToBeforePhotoDrivenAnalysis() },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("重新开始")
                                        }
                                    }
                                }
                            }
                        }
                        is ChatMessage.BadgeUnlockedMessage -> {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                BadgeUnlockedCard(
                                    title = message.title,
                                    description = message.description,
                                    modifier = Modifier.align(Alignment.CenterStart)
                                )
                            }
                        }
                        is ChatMessage.ActionMessage -> {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                AssistChip(
                                    onClick = {
                                        when (message.actionType) {
                                            "SWITCH_SESSION" -> viewModel.createNewSession()
                                            "OPEN_MODEL_MANAGER" -> onNavigateToModelManager()
                                        }
                                    },
                                    label = { Text(message.label) },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp, top = 8.dp)
                                )
                            }
                        }
                        is ChatMessage.ToolCallMessage -> {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = "工具调用",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = message.toolName.toToolDisplayName(),
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                        if (message.argsSummary.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = message.argsSummary,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        is ChatMessage.UiCardMessage -> {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = message.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = message.body,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (message.bullets.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            message.bullets.forEach { bullet ->
                                                Text(
                                                    text = "• $bullet",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(bottom = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        is ChatMessage.DevLogMessage -> {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (message.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = message.title,
                                            fontWeight = FontWeight.Bold,
                                            color = if (message.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = message.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (message.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isThinking && !isReportAnalyzing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.doran_logo),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    DoranLoadingIndicator(
                        indicatorSize = 18.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("朵然在思考…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Quick Actions Bar
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    val paramProgress = listOf(
                        currentParams.weightKg != null,
                        currentParams.shape != null,
                        currentParams.largeLobes > 0 || currentParams.smallLobes > 0,
                        currentParams.variety != null
                    ).count { it }
                    val paramProgressPercent = (paramProgress * 100) / 4
                    AssistChip(
                        onClick = { showSummarySheet = true },
                        enabled = !inputLocked,
                        label = { Text("当前") },
                        leadingIcon = {
                            if (paramProgressPercent >= 100) {
                                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            } else {
                                Text(
                                    text = "${paramProgressPercent}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    )
                }
                if (currentParams.isComplete()) {
                    item {
                        AssistChip(
                            onClick = {
                                when {
                                    currentTask.photos.status != PhotoSetStatus.Ready -> onNavigateToCamera()
                                    else -> viewModel.requestAnalysisFromDock()
                                }
                            },
                            enabled = !inputLocked || currentTask.analysis.status == AnalysisTaskStatus.Completed,
                            label = { Text("询问Doran") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (currentTask.photos.status == PhotoSetStatus.Ready) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                        )
                    }
                }
                item {
                    AssistChip(
                        onClick = { onNavigateToCamera() },
                        enabled = !inputLocked,
                        label = { Text(if (hasValidPhotos) "拍摄" else "缺图") },
                        leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (hasValidPhotos) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = if (hasValidPhotos) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            leadingIconContentColor = if (hasValidPhotos) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
                if (isPhotoInvalid) {
                    item {
                        AssistChip(
                            onClick = { onNavigateToCamera() },
                            enabled = !inputLocked,
                            label = { Text("照片无效，重新拍摄", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.error)
                        )
                    }
                }
                
                val weightDone = currentParams.weightKg != null
                val varietyDone = currentParams.variety != null
                val shapeDone = currentParams.shape != null
                val lobesDone = currentParams.largeLobes > 0 || currentParams.smallLobes > 0
                val isAllDone = weightDone && varietyDone && shapeDone && lobesDone

                item {
                    AssistChip(
                        onClick = {
                            weightDraft = currentParams.weightKg?.toString().orEmpty()
                            showWeightDialog = true
                        },
                        enabled = !inputLocked && !isPhotoInvalid,
                        label = { Text(if (weightDone) "重量 ${currentParams.weightKg}kg" else "重量") },
                        leadingIcon = { StepDot(isDone = weightDone) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = if (weightDone) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    )
                }
                item {
                    AssistChip(
                        onClick = { showVarietySheet = true },
                        enabled = !inputLocked && !isPhotoInvalid,
                        label = { Text(if (varietyDone) "品种 ${currentParams.variety?.displayName}" else "品种") },
                        leadingIcon = { StepDot(isDone = varietyDone) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = if (varietyDone) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    )
                }
                item {
                    AssistChip(
                        onClick = { showShapeSheet = true },
                        enabled = !inputLocked && !isPhotoInvalid,
                        label = { Text(if (shapeDone) "形态 ${currentParams.shape?.displayName}" else "形态") },
                        leadingIcon = { StepDot(isDone = shapeDone) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = if (shapeDone) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    )
                }
                item {
                    AssistChip(
                        onClick = {
                            largeLobesQuick = currentParams.largeLobes.coerceIn(0, 6)
                            smallLobesQuick = currentParams.smallLobes.coerceIn(0, 6)
                            showLobesDialog = true
                        },
                        enabled = !inputLocked && !isPhotoInvalid,
                        label = { Text(if (lobesDone) "房数 ${currentParams.largeLobes}/${currentParams.smallLobes}" else "房数") },
                        leadingIcon = { StepDot(isDone = lobesDone) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = if (lobesDone) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    )
                }
                item {
                    val analysisStatus = currentTask.analysis.status
                    val dockLabel = when {
                        analysisStatus == AnalysisTaskStatus.Running -> "分析中"
                        currentTask.photos.status != PhotoSetStatus.Ready -> "去拍摄"
                        !currentTask.params.isComplete() -> "补参数"
                        analysisStatus == AnalysisTaskStatus.Completed -> "重新分析"
                        else -> "开始分析"
                    }
                    AssistChip(
                        onClick = {
                            when {
                                currentTask.photos.status != PhotoSetStatus.Ready -> onNavigateToCamera()
                                !currentTask.params.isComplete() -> viewModel.requestEditParams()
                                else -> viewModel.requestAnalysisFromDock()
                            }
                        },
                        enabled = !inputLocked || analysisStatus == AnalysisTaskStatus.Completed,
                        label = { Text(dockLabel) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (currentTask.isReadyToAnalyze) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    )
                }
            }

            // Input Area
            ChatInputBar(
                onSend = { text -> viewModel.onUserSendMessage(text) },
                onSendAudio = { audioPath, prompt, durationMs -> viewModel.onUserSendAudio(audioPath, prompt, durationMs) },
                onAddImage = { fromCamera ->
                    viewModel.onAddImage(fromCamera)
                    if (fromCamera) {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            chatCameraCapture.launch(null)
                        } else {
                            chatCameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    } else {
                        chatAlbumPicker.launch("image/*")
                    }
                },
                enabled = !inputLocked
            )
        }
        }
    }

    analysisDetailsMessageId?.let { selectedId ->
        val selected = remember(messages, selectedId) {
            messages.firstOrNull { it.id == selectedId } as? ChatMessage.AnalysisProgressMessage
        }
        if (selected != null) {
            ModalBottomSheet(
                onDismissRequest = { analysisDetailsMessageId = null },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                AnalysisProgressDetailsSheet(
                    message = selected,
                    onCancel = { viewModel.cancelPhotoDrivenAnalysis(); analysisDetailsMessageId = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                )
                Spacer(modifier = Modifier.height(28.dp))
            }
        } else {
            analysisDetailsMessageId = null
        }
    }

    if (showModelSheet) {
        val files = remember(activeModelPath, showModelSheet) {
            val out = LinkedHashMap<String, File>()
            fun add(dir: File?) {
                if (dir == null || !dir.exists()) return
                dir.listFiles()
                    ?.asSequence()
                    ?.filter { it.isFile && it.extension.equals("litertlm", ignoreCase = true) }
                    ?.forEach { out[it.absolutePath] = it }
            }

            val external = context.getExternalFilesDir(null)
            add(external?.let { File(it, "models") })
            add(external)
            out.values.sortedByDescending { it.lastModified() }
        }

        ModalBottomSheet(
            onDismissRequest = { showModelSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text("切换模型", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(14.dp))

                if (files.isEmpty()) {
                    Text("暂无模型文件，请先去模型管理导入。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { showModelSheet = false; onNavigateToModelManager() }) {
                        Text("打开模型管理")
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    return@ModalBottomSheet
                }

                files.forEach { file ->
                    val isActive = file.absolutePath == activeModelPath
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = !isModelLoading) {
                                viewModel.switchActiveModel(file.absolutePath)
                                showModelSheet = false
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(file.name, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isActive) "当前默认" else "点击切换",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isActive) {
                            Text("已选", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Spacer(modifier = Modifier.height(6.dp))
                TextButton(onClick = { showModelSheet = false; onNavigateToModelManager() }) {
                    Text("打开模型管理")
                }
                Spacer(modifier = Modifier.height(18.dp))
            }
        }
    }

    if (showWeightDialog) {
        AlertDialog(
            onDismissRequest = { showWeightDialog = false },
            title = { Text("输入重量") },
            text = {
                OutlinedTextField(
                    value = weightDraft,
                    onValueChange = { weightDraft = it },
                    label = { Text("重量 (kg)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val w = weightDraft.trim().toFloatOrNull()
                        if (w != null) {
                            viewModel.setWeightFromQuick(w)
                            showWeightDialog = false
                        }
                    },
                    enabled = weightDraft.trim().toFloatOrNull() != null
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showWeightDialog = false }) { Text("取消") }
            }
        )
    }

    if (showLobesDialog) {
        AlertDialog(
            onDismissRequest = { showLobesDialog = false },
            title = { Text("输入房数") },
            text = {
                Column {
                    Text("饱满大房：$largeLobesQuick", fontWeight = FontWeight.Bold)
                    Slider(
                        value = largeLobesQuick.toFloat(),
                        onValueChange = { largeLobesQuick = it.roundToInt().coerceIn(0, 6) },
                        valueRange = 0f..6f,
                        steps = 5
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("干瘪小房：$smallLobesQuick", fontWeight = FontWeight.Bold)
                    Slider(
                        value = smallLobesQuick.toFloat(),
                        onValueChange = { smallLobesQuick = it.roundToInt().coerceIn(0, 6) },
                        valueRange = 0f..6f,
                        steps = 5
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setLobesFromQuick(largeLobesQuick, smallLobesQuick)
                        showLobesDialog = false
                    },
                    enabled = true
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showLobesDialog = false }) { Text("取消") }
            }
        )
    }

    if (showShapeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showShapeSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text("选择形态", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                com.winter.durianai.domain.model.DurianShape.values().forEach { shape ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                viewModel.setShapeFromQuick(shape)
                                showShapeSheet = false
                            }
                            .padding(vertical = 10.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentParams.shape == shape,
                            onClick = {
                                viewModel.setShapeFromQuick(shape)
                                showShapeSheet = false
                            }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(shape.displayName, fontWeight = if (currentParams.shape == shape) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
            }
        }
    }

    if (showFormDetailsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFormDetailsSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text("表单详情", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (formDetailsStatus) {
                        InputFormStatus.Pending -> "待分析"
                        InputFormStatus.Analyzing -> "分析中"
                        InputFormStatus.Done -> "分析完成"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                val rows = listOf(
                    "品种" to (formDetailsParams.variety?.displayName ?: "未填写"),
                    "重量" to (formDetailsParams.weightKg?.let { "${it}kg" } ?: "未填写"),
                    "房数" to "${formDetailsParams.largeLobes}/${formDetailsParams.smallLobes}",
                    "形态" to (formDetailsParams.shape?.displayName ?: "未填写"),
                    "照片" to (if (hasValidPhotos) "已拍齐" else "缺图")
                )
                rows.forEach { (k, v) ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(k, modifier = Modifier.width(60.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Spacer(modifier = Modifier.height(6.dp))
                TextButton(onClick = { showFormDetailsSheet = false }) { Text("关闭") }
                Spacer(modifier = Modifier.height(18.dp))
            }
        }
    }

    if (showSessionManageSheet) {
        val sessionId = currentSessionId
        ModalBottomSheet(
            onDismissRequest = { showSessionManageSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text("会话管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = sessionTitleDraft,
                    onValueChange = { sessionTitleDraft = it },
                    label = { Text("会话名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            if (sessionId != null) {
                                viewModel.renameSession(sessionId, sessionTitleDraft)
                            }
                            showSessionManageSheet = false
                        },
                        modifier = Modifier.weight(1f),
                        enabled = sessionId != null && sessionTitleDraft.trim().isNotBlank()
                    ) {
                        Text("重命名")
                    }
                    Button(
                        onClick = {
                            if (sessionId != null) {
                                viewModel.deleteSession(sessionId)
                            }
                            showSessionManageSheet = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.onError)
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showSessionPickerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSessionPickerSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("会话", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(
                        onClick = {
                            val currentSession = sessions.find { it.id == currentSessionId }
                            sessionTitleDraft = currentSession?.title.orEmpty()
                            showSessionManageSheet = true
                            showSessionPickerSheet = false
                        },
                        enabled = currentSessionId != null
                    ) {
                        Text("管理当前")
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                sessions.asReversed().forEach { session ->
                    val isCurrent = session.id == currentSessionId
                    val lastResult = session.messages.asReversed()
                        .firstOrNull { it is ChatMessage.ResultReportMessage } as? ChatMessage.ResultReportMessage
                    val statusText = if (lastResult != null) "已分析 · ${lastResult.score}分" else "未分析"

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable {
                                viewModel.switchSession(session.id)
                                showSessionPickerSheet = false
                            },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DurianEmoji(size = 26.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    session.title,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isCurrent) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text("当前") },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        viewModel.createNewSession()
                        showSessionPickerSheet = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("挑选其他榴莲")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showVarietySheet) {
        ModalBottomSheet(
            onDismissRequest = { showVarietySheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text("选择品种", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                DurianVariety.values().forEach { variety ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                viewModel.setVarietyFromQuick(variety)
                                showVarietySheet = false
                            }
                            .padding(vertical = 10.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentParams.variety == variety,
                            onClick = {
                                viewModel.setVarietyFromQuick(variety)
                                showVarietySheet = false
                            }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(variety.displayName, fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (imagePreviewResId != null) {
        Dialog(onDismissRequest = { imagePreviewResId = null }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp)
            ) {
                Image(
                    painter = painterResource(id = imagePreviewResId!!),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }

    if (imagePreviewPath != null) {
        Dialog(onDismissRequest = { imagePreviewPath = null }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp)
            ) {
                AsyncImage(
                    model = File(imagePreviewPath!!),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }

    if (showSummarySheet) {
        ModalBottomSheet(
            onDismissRequest = { showSummarySheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            val lastResult = remember(messages) {
                (messages.asReversed().firstOrNull { it is ChatMessage.ResultReportMessage } as? ChatMessage.ResultReportMessage)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Text("当前榴莲", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("当前参数", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        SummaryRow("品种", currentParams.variety?.displayName ?: "未填写")
                        SummaryRow("重量", currentParams.weightKg?.let { "${it}kg" } ?: "未填写")
                        SummaryRow(
                            "房数",
                            if (currentParams.largeLobes > 0 || currentParams.smallLobes > 0) {
                                "${currentParams.largeLobes}/${currentParams.smallLobes}"
                            } else {
                                "未填写"
                            }
                        )
                        SummaryRow("形态", currentParams.shape?.displayName ?: "未填写")
                        SummaryRow(
                            "照片",
                            when (currentTask.photos.status) {
                                PhotoSetStatus.Ready -> "已完成 ${currentTask.photos.count}/5"
                                PhotoSetStatus.Invalid -> "无效，需要重拍"
                                PhotoSetStatus.Missing -> "缺少，当前 ${currentTask.photos.count}/5"
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text("分析结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))

                if (lastResult == null) {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "暂无分析结果",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    ResultReportCard(
                        paramsSnapshot = lastResult.paramsSnapshot,
                        imageResIds = lastResult.imageResIds,
                        imagePaths = lastResult.imagePaths,
                        score = lastResult.score,
                        level = lastResult.level,
                        reportText = lastResult.reportText,
                        onImageResClick = { imagePreviewResId = it },
                        onImagePathClick = { imagePreviewPath = it }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "备注：AI 可能会犯错，仅供参考。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = { showSummarySheet = false },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("关闭")
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
    Spacer(modifier = Modifier.height(6.dp))
}

private fun saveBitmapToChatImage(context: Context, bitmap: Bitmap): File? {
    val dir = File(context.cacheDir, "chat_images").apply { mkdirs() }
    val file = File(dir, "chat_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
    return runCatching {
        writeOptimizedChatBitmap(bitmap = bitmap, outFile = file, maxOutputSize = 1536, quality = 84)
        file
    }.getOrNull()
}

private fun copyChatImageToCache(context: Context, uri: Uri): File? {
    val dir = File(context.cacheDir, "chat_images").apply { mkdirs() }
    val rawFile = File(dir, "chat_raw_${System.currentTimeMillis()}_${UUID.randomUUID()}.${guessImageExtension(context, uri)}")
    val compressedFile = File(dir, "chat_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(rawFile).use { output ->
                input.copyTo(output)
            }
        } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(rawFile.absolutePath, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return rawFile

        var sample = 1
        val maxSide = max(srcW, srcH)
        while (maxSide / sample > 1536) {
            sample *= 2
        }

        val decoded = BitmapFactory.decodeFile(
            rawFile.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = max(1, sample) }
        ) ?: return rawFile

        val oriented = orientBitmapFromUri(context, uri, decoded)
        writeOptimizedChatBitmap(bitmap = oriented, outFile = compressedFile, maxOutputSize = 1536, quality = 82)
        try {
            rawFile.delete()
        } catch (_: Exception) {
        }
        compressedFile
    }.getOrNull()
}

private fun writeOptimizedChatBitmap(
    bitmap: Bitmap,
    outFile: File,
    maxOutputSize: Int,
    quality: Int
) {
    val optimized = if (bitmap.width > maxOutputSize || bitmap.height > maxOutputSize) {
        val scale = maxOutputSize.toFloat() / max(bitmap.width, bitmap.height).toFloat()
        Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).toInt()),
            max(1, (bitmap.height * scale).toInt()),
            true
        )
    } else {
        bitmap
    }
    FileOutputStream(outFile).use { output ->
        optimized.compress(Bitmap.CompressFormat.JPEG, quality, output)
    }
}

private fun orientBitmapFromUri(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
    }
    if (matrix.isIdentity) return bitmap
    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrDefault(bitmap)
}

private fun guessImageExtension(context: Context, uri: Uri): String {
    val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
    return when {
        "png" in mime -> "png"
        "webp" in mime -> "webp"
        "heic" in mime || "heif" in mime -> "heic"
        else -> "jpg"
    }
}

private fun String.toToolDisplayName(): String {
    return split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part ->
            part.replaceFirstChar { char -> char.uppercase() }
        }
}

@Composable
private fun StepDot(isDone: Boolean) {
    if (isDone) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    } else {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

@Composable
private fun ResultReportCard(
    paramsSnapshot: DurianParameters,
    imageResIds: List<Int>,
    imagePaths: List<String>,
    score: Int,
    level: Int,
    reportText: String,
    onImageResClick: (Int) -> Unit,
    onImagePathClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("终极评测", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "$score 分",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text("等级 $level") },
                    leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                )
            }

            if (imagePaths.isNotEmpty() || imageResIds.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("图片", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (imagePaths.isNotEmpty()) {
                        imagePaths.take(12).forEach { path ->
                            AsyncImage(
                                model = File(path),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onImagePathClick(path) },
                                contentScale = ContentScale.Crop
                            )
                        }
                    } else {
                        imageResIds.take(12).forEach { resId ->
                            Image(
                                painter = painterResource(id = resId),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onImageResClick(resId) },
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("参数", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(modifier = Modifier.height(8.dp))

            val tags = remember(paramsSnapshot) {
                buildList {
                    paramsSnapshot.variety?.displayName?.let { add("品种 $it") }
                    paramsSnapshot.weightKg?.let { add("重量 ${it}kg") }
                    paramsSnapshot.shape?.displayName?.let { add("形态 $it") }
                    if (paramsSnapshot.largeLobes > 0) add("大房 ${paramsSnapshot.largeLobes}")
                    if (paramsSnapshot.smallLobes > 0) add("小房 ${paramsSnapshot.smallLobes}")
                }
            }

            if (tags.isEmpty()) {
                Text(
                    "暂无参数",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tags.forEach { label ->
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text(label) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("建议", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                reportText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun BadgeUnlockedCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun AudioMessageBubble(
    durationMs: Long,
    prompt: String?,
    isFromUser: Boolean,
    modifier: Modifier = Modifier
) {
    val seconds = (durationMs / 1000).coerceAtLeast(1)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isFromUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            tint = if (isFromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                "语音消息 ${seconds}s",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (isFromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            if (!prompt.isNullOrBlank()) {
                Text(
                    prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isFromUser) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
