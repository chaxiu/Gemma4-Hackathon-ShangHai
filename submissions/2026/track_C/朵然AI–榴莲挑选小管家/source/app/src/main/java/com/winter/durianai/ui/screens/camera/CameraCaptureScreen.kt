package com.winter.durianai.ui.screens.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.winter.durianai.ui.components.DoranLoadingIndicator
import com.winter.durianai.ui.screens.agent.AgentChatViewModel
import com.winter.durianai.ui.screens.agent.models.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.exifinterface.media.ExifInterface
import com.winter.durianai.data.local.prefs.UserPreferencesRepository
import com.winter.durianai.domain.model.PhotoQualityProfile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private data class PhotoQualityReport(
    val isOk: Boolean,
    val reasons: List<String>,
    val blurScore: Double,
    val meanLuma: Double,
    val stdLuma: Double
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun CameraCaptureScreen(
    agentChatViewModel: AgentChatViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember { UserPreferencesRepository(context) }
    val activeModelPath by prefs.activeModelPath.collectAsState(initial = null)
    val pendingVisionSeedImagePath by agentChatViewModel.pendingVisionSeedImagePath.collectAsState()
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    val hasCameraPermission = cameraPermission.status is PermissionStatus.Granted

    val steps = remember { listOf("上面", "下面", "左侧", "右侧", "正面") }
    var stepIndex by remember { mutableStateOf(0) }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    val cameraSelector = remember(lensFacing) { CameraSelector.Builder().requireLensFacing(lensFacing).build() }

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    var isBinding by remember { mutableStateOf(false) }
    var isViewfinderEnabled by remember { mutableStateOf(false) }
    var capturedFile by remember { mutableStateOf<File?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var qualityReport by remember { mutableStateOf<PhotoQualityReport?>(null) }
    var doranCheck by remember { mutableStateOf<AgentChatViewModel.DoranPhotoCheck?>(null) }
    var isDoranChecking by remember { mutableStateOf(false) }
    val capturedPaths = remember { mutableStateOf<List<String?>>(List(steps.size) { null }) }
    val qualityProfiles = remember { mutableStateOf<List<PhotoQualityProfile?>>(List(steps.size) { null }) }
    var isReviewingExisting by remember { mutableStateOf(false) }
    val isNonDurian = doranCheck?.issues?.contains("non_durian") == true
    val hasBlockingVisionIssue = doranCheck?.issues.orEmpty().any {
        it in setOf("non_durian", "wrong_angle", "cropped", "occluded", "cluttered", "too_far")
    }
    var didInitFromHistory by remember { mutableStateOf(false) }

    fun clearReview() {
        capturedFile = null
        qualityReport = null
        doranCheck = null
        isDoranChecking = false
        isCapturing = false
        isReviewingExisting = false
    }

    fun replaceStepPhoto(index: Int, file: File) {
        val oldPath = capturedPaths.value.getOrNull(index)
        if (!oldPath.isNullOrBlank() && oldPath != file.absolutePath) {
            try {
                File(oldPath).delete()
            } catch (_: Exception) {
            }
        }
        val next = capturedPaths.value.toMutableList()
        next[index] = file.absolutePath
        capturedPaths.value = next
    }

    fun replaceStepQualityProfile(index: Int, profile: PhotoQualityProfile?) {
        val next = qualityProfiles.value.toMutableList()
        next[index] = profile
        qualityProfiles.value = next
    }

    LaunchedEffect(pendingVisionSeedImagePath) {
        if (didInitFromHistory) return@LaunchedEffect
        val pendingSeed = pendingVisionSeedImagePath
        if (!pendingSeed.isNullOrBlank() && File(pendingSeed).exists()) {
            capturedPaths.value = List(steps.size) { idx -> if (idx == 0) pendingSeed else null }
            qualityProfiles.value = List(steps.size) { null }
            stepIndex = 1
            agentChatViewModel.consumePendingVisionSeedImage()
            didInitFromHistory = true
            return@LaunchedEffect
        }
        val lastStrip =
            agentChatViewModel.messages.value.asReversed()
                .filterIsInstance<ChatMessage.ImageStripMessage>()
                .firstOrNull { it.imagePaths.size >= steps.size }
        if (lastStrip != null) {
            capturedPaths.value = List(steps.size) { idx -> lastStrip.imagePaths.getOrNull(idx) }
            qualityProfiles.value = List(steps.size) { null }
            val firstMissing = capturedPaths.value.indexOfFirst { it.isNullOrBlank() }
            stepIndex = if (firstMissing == -1) 0 else firstMissing
        }
        didInitFromHistory = true
    }

    fun bindCamera() {
        if (!hasCameraPermission) return
        isBinding = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val provider = future.get()
                provider.unbindAll()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
                isBinding = false
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    LaunchedEffect(hasCameraPermission, lensFacing, isViewfinderEnabled) {
        if (hasCameraPermission) {
            agentChatViewModel.maybeInitEngine(reason = "拍照页需要图片检查")
            if (isViewfinderEnabled) {
                bindCamera()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    fun analyzeAndSetReport(file: File, stepLabel: String) {
        scope.launch {
            val bitmap = decodeForAnalysis(file, maxSize = 360) ?: run {
                qualityReport = PhotoQualityReport(
                    isOk = false,
                    reasons = listOf("照片读取失败"),
                    blurScore = 0.0,
                    meanLuma = 0.0,
                    stdLuma = 0.0
                )
                doranCheck = AgentChatViewModel.DoranPhotoCheck(
                    ok = false,
                    reason = "照片读取失败",
                    issues = listOf("other")
                )
                return@launch
            }
            val report = quickQualityCheck(bitmap)
            qualityReport = report

            val visionInput = withContext(kotlinx.coroutines.Dispatchers.IO) {
                createVisionInputFile(file, maxOutputSize = 640)
            }
            isDoranChecking = true
            doranCheck = agentChatViewModel.checkPhotoWithDoran(
                imagePath = (visionInput ?: file).absolutePath,
                stepLabel = stepLabel,
                blurScore = report.blurScore,
                meanLuma = report.meanLuma,
                stdLuma = report.stdLuma,
                reasons = report.reasons
            )
            isDoranChecking = false
            if (visionInput != null) {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        visionInput.delete()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    val pickFromAlbum = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val targetIndex = stepIndex
        scope.launch {
            val outFile = File(context.cacheDir, "album_${UUID.randomUUID()}.jpg")
            val copied = withContext(Dispatchers.IO) { copyUriToFile(context, uri, outFile) }
            if (!copied) {
                capturedFile = outFile
                qualityReport = PhotoQualityReport(
                    isOk = false,
                    reasons = listOf("相册图片读取失败"),
                    blurScore = 0.0,
                    meanLuma = 0.0,
                    stdLuma = 0.0
                )
                doranCheck = AgentChatViewModel.DoranPhotoCheck(
                    ok = false,
                    reason = "相册图片读取失败",
                    issues = listOf("other")
                )
                return@launch
            }
            val ok = withContext(Dispatchers.IO) { cropInPlaceToCenterSquare(outFile, maxOutputSize = 1536) }
            capturedFile = outFile
            isReviewingExisting = false
            qualityReport = null
            doranCheck = null
            isDoranChecking = false
            if (!ok) {
                qualityReport = PhotoQualityReport(
                    isOk = false,
                    reasons = listOf("裁切失败，请换一张"),
                    blurScore = 0.0,
                    meanLuma = 0.0,
                    stdLuma = 0.0
                )
                doranCheck = AgentChatViewModel.DoranPhotoCheck(
                    ok = false,
                    reason = "裁切失败，请换一张",
                    issues = listOf("other")
                )
                return@launch
            }
            replaceStepPhoto(targetIndex, outFile)
            analyzeAndSetReport(outFile, stepLabel = steps[targetIndex])
        }
    }

    fun onConfirmUsePhoto() {
        if (hasBlockingVisionIssue) {
            return
        }
        val file = capturedFile ?: return
        val report = qualityReport
        val check = doranCheck
        val captureIndex = stepIndex
        val angleLabel = steps[captureIndex]
        val stableFile = persistCapturedPhoto(context, file, angleLabel) ?: file
        val next = capturedPaths.value.toMutableList()
        next[captureIndex] = stableFile.absolutePath
        capturedPaths.value = next
        replaceStepQualityProfile(
            captureIndex,
            PhotoQualityProfile(
                angleLabel = angleLabel,
                imagePath = stableFile.absolutePath,
                ok = report?.isOk != false && check?.ok == true,
                reason = check?.reason?.takeIf { it.isNotBlank() }
                    ?: report?.reasons?.joinToString("、").orEmpty().ifBlank { "未完成视觉检查" },
                issues = (report?.reasons.orEmpty() + check?.issues.orEmpty()).distinct(),
                blurScore = report?.blurScore ?: 0.0,
                meanLuma = report?.meanLuma ?: 0.0,
                stdLuma = report?.stdLuma ?: 0.0,
                forcedUse = report?.isOk == false || check?.ok == false
            )
        )
        capturedFile = stableFile
        clearReview()
        val done = capturedPaths.value.all { !it.isNullOrBlank() }
        if (done) {
            agentChatViewModel.onVisionCaptureFinished(
                imagePaths = capturedPaths.value.filterNotNull(),
                qualityProfiles = qualityProfiles.value.filterNotNull()
            )
            onNavigateBack()
            return
        }
        val nextMissing = (stepIndex + 1..steps.lastIndex).firstOrNull { capturedPaths.value[it].isNullOrBlank() }
            ?: (0..steps.lastIndex).firstOrNull { capturedPaths.value[it].isNullOrBlank() }
            ?: stepIndex
        stepIndex = nextMissing
    }
    
    val navigateBackWithInvalidIfNeeded = {
        if (isNonDurian) {
            val reason = doranCheck?.reason?.takeIf { it.isNotBlank() } ?: "检测到非榴莲图片，照片无效"
            agentChatViewModel.onVisionCaptureInvalid(reason)
        }
        onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拍摄：${steps[stepIndex]}（${stepIndex + 1}/${steps.size}）") },
                navigationIcon = {
                    IconButton(onClick = navigateBackWithInvalidIfNeeded) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        }
                    ) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Switch")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (!hasCameraPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("需要相机权限才能拍摄", color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                        Text("授权相机权限")
                    }
                }
                return@Scaffold
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val viewfinderModifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.Black)

                Box(modifier = viewfinderModifier) {
                    if (capturedFile == null) {
                        if (isViewfinderEnabled) {
                            AndroidView(
                                factory = { previewView },
                                modifier = Modifier.fillMaxSize()
                            )
                            CaptureOverlay(modifier = Modifier.fillMaxSize())
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.82f))
                                    .clickable {
                                        isViewfinderEnabled = true
                                        if (hasCameraPermission) {
                                            bindCamera()
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "取景器已关闭",
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "触碰这里打开取景器",
                                        color = Color.White.copy(alpha = 0.82f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        if (isViewfinderEnabled && (isBinding || isCapturing)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                DoranLoadingIndicator(
                                    indicatorSize = 44.dp,
                                    color = Color.White,
                                    backgroundColor = Color.White.copy(alpha = 0.18f)
                                )
                            }
                        }
                    } else {
                        val file = capturedFile!!
                        AsyncImage(
                            model = file,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )

                        if (!isReviewingExisting && (isDoranChecking || qualityReport == null)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    DoranLoadingIndicator(
                                        indicatorSize = 54.dp,
                                        color = Color.White,
                                        backgroundColor = Color.White.copy(alpha = 0.18f)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        "Doran 正在检查照片…",
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "角度：${steps[stepIndex]}",
                                        color = Color.White.copy(alpha = 0.85f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        if (!isReviewingExisting && !isDoranChecking && doranCheck != null) {
                            val prefix = if (doranCheck?.ok == true) "✅ Doran：可以用" else "⚠️ Doran：建议重拍"
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(10.dp)
                                    .fillMaxWidth()
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.62f)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "$prefix（${doranCheck?.reason.orEmpty()}）",
                                        color = Color.White.copy(alpha = 0.92f),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    steps.forEachIndexed { index, label ->
                        val path = capturedPaths.value.getOrNull(index)
                        val isSelected = index == stepIndex
                        val borderColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.20f)
                        val alpha = if (path != null || isSelected) 1f else 0.65f
                        Column(
                            modifier = Modifier
                                .width(72.dp)
                                .alpha(alpha)
                                .clickable(
                                    enabled = !isDoranChecking && !isCapturing && !path.isNullOrBlank()
                                ) {
                                    stepIndex = index
                                    val p = capturedPaths.value.getOrNull(index).orEmpty()
                                    capturedFile = File(p)
                                    isReviewingExisting = true
                                    qualityReport = null
                                    doranCheck = null
                                    isDoranChecking = false
                                },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(14.dp))
                                    .background(Color.Black.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!path.isNullOrBlank()) {
                                    AsyncImage(
                                        model = File(path),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text(
                                        text = "${index + 1}",
                                        color = Color.White.copy(alpha = 0.85f),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = label,
                                color = Color.White.copy(alpha = if (isSelected) 0.95f else 0.78f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                if (capturedFile == null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.55f)),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(
                                "请拍摄：${steps[stepIndex]}（${stepIndex + 1}/${steps.size}）",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                if (isViewfinderEnabled) "取景框为 1:1，拍齐五个角度后再开始分析" else "取景器默认关闭，触碰上方画面后再拍摄",
                                color = Color.White.copy(alpha = 0.85f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.width(96.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            capturedPaths.value = List(steps.size) { null }
                                            qualityProfiles.value = List(steps.size) { null }
                                            stepIndex = 0
                                            clearReview()
                                        },
                                        enabled = !isCapturing,
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                                    ) {
                                        Icon(Icons.Default.Cached, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("重置", maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = { pickFromAlbum.launch("image/*") },
                                        enabled = !isCapturing,
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                                    ) {
                                        Text("相册", maxLines = 1)
                                    }
                                }

                                Spacer(modifier = Modifier.weight(1f))
                                CaptureButton(
                                    enabled = isViewfinderEnabled && !isCapturing,
                                    onClick = {
                                        val captureStep = stepIndex
                                        isCapturing = true
                                        val file = File(context.cacheDir, "cap_${UUID.randomUUID()}.jpg")
                                        val output = ImageCapture.OutputFileOptions.Builder(file).build()
                                        imageCapture.takePicture(
                                            output,
                                            ContextCompat.getMainExecutor(context),
                                            object : ImageCapture.OnImageSavedCallback {
                                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                                    scope.launch {
                                                        val ok = withContext(Dispatchers.IO) {
                                                            cropInPlaceToCenterSquare(file, maxOutputSize = 1536)
                                                        }
                                                        capturedFile = file
                                                        isCapturing = false
                                                        if (!ok) {
                                                            qualityReport = PhotoQualityReport(
                                                                isOk = false,
                                                                reasons = listOf("裁切失败，请重拍"),
                                                                blurScore = 0.0,
                                                                meanLuma = 0.0,
                                                                stdLuma = 0.0
                                                            )
                                                            doranCheck = AgentChatViewModel.DoranPhotoCheck(
                                                                ok = false,
                                                                reason = "裁切失败，请重拍",
                                                                issues = listOf("other")
                                                            )
                                                        } else {
                                                            replaceStepPhoto(captureStep, file)
                                                            isReviewingExisting = false
                                                            qualityReport = null
                                                            doranCheck = null
                                                            analyzeAndSetReport(file, stepLabel = steps[captureStep])
                                                        }
                                                    }
                                                }

                                                override fun onError(exception: ImageCaptureException) {
                                                    isCapturing = false
                                                }
                                            }
                                        )
                                    }
                                )
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    val file = capturedFile!!
                    ReviewOverlay(
                        stepLabel = steps[stepIndex],
                        report = qualityReport,
                        doranCheck = doranCheck,
                        isDoranChecking = isDoranChecking,
                        isNonDurian = isNonDurian,
                        hasBlockingVisionIssue = hasBlockingVisionIssue,
                        isReviewingExisting = isReviewingExisting,
                        modifier = Modifier.fillMaxWidth(),
                        onRetake = {
                            try {
                                file.delete()
                            } catch (_: Exception) {
                            }
                            val next = capturedPaths.value.toMutableList()
                            next[stepIndex] = null
                            capturedPaths.value = next
                            replaceStepQualityProfile(stepIndex, null)
                            clearReview()
                        },
                        onBackToCapture = {
                            clearReview()
                        },
                        onUse = { onConfirmUsePhoto() }
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val padding = size.minDimension * 0.08f
        val boxSize = min(size.width - padding * 2, size.height - padding * 2)
        val topLeft = Offset((size.width - boxSize) / 2f, (size.height - boxSize) / 2f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.80f),
            topLeft = topLeft,
            size = Size(boxSize, boxSize),
            cornerRadius = CornerRadius(26f, 26f),
            style = Stroke(width = 5f, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun CaptureButton(enabled: Boolean, onClick: () -> Unit) {
    val outer = if (enabled) Color.White else Color.White.copy(alpha = 0.5f)
    val inner = if (enabled) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f)
    Box(
        modifier = Modifier
            .size(78.dp)
            .background(inner, shape = RoundedCornerShape(999.dp)),
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(62.dp),
            shape = RoundedCornerShape(999.dp)
        ) {}
        Canvas(modifier = Modifier.size(78.dp)) {
            drawCircle(color = outer, style = Stroke(width = 6f))
        }
    }
}

@Composable
private fun ReviewOverlay(
    stepLabel: String,
    report: PhotoQualityReport?,
    doranCheck: AgentChatViewModel.DoranPhotoCheck?,
    isDoranChecking: Boolean,
    isNonDurian: Boolean,
    hasBlockingVisionIssue: Boolean,
    isReviewingExisting: Boolean,
    modifier: Modifier = Modifier,
    onRetake: () -> Unit,
    onBackToCapture: () -> Unit,
    onUse: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.60f)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("检查：$stepLabel", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))

                if (isReviewingExisting) {
                    Text("已拍摄，可查看或重拍此角度", color = Color.White.copy(alpha = 0.9f))
                } else {
                    val reasons = report?.reasons.orEmpty()
                    if (report == null) {
                        Text("质量检查中…", color = Color.White.copy(alpha = 0.9f))
                    } else if (report.isOk) {
                        Text("基础质量：可以用", color = Color.White)
                    } else {
                        Text("基础质量：建议重拍", color = Color.White)
                        if (reasons.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(reasons.joinToString(separator = "、"), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    when {
                        isDoranChecking -> Text("视角检查中…", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                        doranCheck?.ok == true -> Text("视角检查：${doranCheck.reason}", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                        doranCheck?.ok == false -> Text("视角检查：${doranCheck.reason}", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        if (isNonDurian || hasBlockingVisionIssue) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isNonDurian) {
                    "检测为非榴莲，照片无效，请重新拍摄。"
                } else {
                    "此角度照片会影响分析准确性，建议重拍。"
                },
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isReviewingExisting) {
                OutlinedButton(onClick = onBackToCapture, modifier = Modifier.weight(1f)) { Text("返回取景") }
                Button(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("重拍") }
            } else {
                OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("重拍") }
                val fastOk = report?.isOk != false
                val doranOk = doranCheck?.ok == true
                val canUse = fastOk && doranOk && !isDoranChecking && !hasBlockingVisionIssue
                Button(onClick = onUse, modifier = Modifier.weight(1f), enabled = canUse) { Text("使用") }
            }
        }

        if (!isReviewingExisting && !isNonDurian && !hasBlockingVisionIssue && (isDoranChecking || doranCheck?.ok == false || report?.isOk == false)) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onUse, modifier = Modifier.fillMaxWidth()) { Text("仍然使用（不推荐）") }
        }
    }
}

private fun decodeForAnalysis(file: File, maxSize: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    val (w, h) = options.outWidth to options.outHeight
    if (w <= 0 || h <= 0) return null
    var sample = 1
    while (w / sample > maxSize || h / sample > maxSize) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = max(1, sample) }
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}

private fun quickQualityCheck(bitmap: Bitmap): PhotoQualityReport {
    val (blur, mean, std) = lumaAndBlurScore(bitmap)
    val composition = compositionCheck(bitmap)
    val reasons = mutableListOf<String>()

    if (mean < 38) reasons.add("环境过暗")
    if (mean > 225) reasons.add("过曝")
    if (std < 14) reasons.add("对比度偏低")
    if (blur < 90) reasons.add("画面不清晰（建议对焦后再拍）")
    reasons.addAll(composition)

    val ok = reasons.isEmpty()
    return PhotoQualityReport(
        isOk = ok,
        reasons = reasons,
        blurScore = blur,
        meanLuma = mean,
        stdLuma = std
    )
}

private fun compositionCheck(bitmap: Bitmap): List<String> {
    val w = bitmap.width
    val h = bitmap.height
    if (w < 24 || h < 24) return listOf("画面分辨率过低")

    val stride = max(1, min(w, h) / 180)
    val threshold = 26.0

    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE
    var strongCount = 0
    var totalCount = 0

    fun lumaAt(x: Int, y: Int): Double {
        val c = bitmap.getPixel(x, y)
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    var y = stride
    while (y < h - stride) {
        var x = stride
        while (x < w - stride) {
            val center = lumaAt(x, y)
            val gx = lumaAt(x + stride, y) - lumaAt(x - stride, y)
            val gy = lumaAt(x, y + stride) - lumaAt(x, y - stride)
            val g = abs(gx) + abs(gy) + abs(center - lumaAt(x + stride, y + stride))
            totalCount++
            if (g > threshold) {
                strongCount++
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
            }
            x += stride
        }
        y += stride
    }

    if (strongCount < max(36, totalCount / 320)) {
        return listOf("画面主体不明显（可能不是榴莲或离得太远）")
    }

    val boxW = (maxX - minX + 1).toDouble()
    val boxH = (maxY - minY + 1).toDouble()
    val areaRatio = (boxW * boxH) / (w.toDouble() * h.toDouble())

    val reasons = mutableListOf<String>()
    if (areaRatio < 0.08) reasons.add("主体太小（离得太远）")
    return reasons
}

private fun lumaAndBlurScore(bitmap: Bitmap): Triple<Double, Double, Double> {
    val w = bitmap.width
    val h = bitmap.height
    val stride = max(1, min(w, h) / 180)
    var count = 0

    var sum = 0.0
    var sumSq = 0.0
    var lapSum = 0.0
    var lapSumSq = 0.0
    var lapCount = 0

    fun lumaAt(x: Int, y: Int): Double {
        val c = bitmap.getPixel(x, y)
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    var y = stride
    while (y < h - stride) {
        var x = stride
        while (x < w - stride) {
            val l = lumaAt(x, y)
            sum += l
            sumSq += l * l
            count++

            val lap = -4 * l + lumaAt(x - stride, y) + lumaAt(x + stride, y) + lumaAt(x, y - stride) + lumaAt(x, y + stride)
            val al = abs(lap)
            lapSum += al
            lapSumSq += al * al
            lapCount++
            x += stride
        }
        y += stride
    }

    val mean = if (count > 0) sum / count else 0.0
    val variance = if (count > 0) (sumSq / count) - mean * mean else 0.0
    val std = max(0.0, variance).let { kotlin.math.sqrt(it) }

    val lapMean = if (lapCount > 0) lapSum / lapCount else 0.0
    val lapVar = if (lapCount > 0) (lapSumSq / lapCount) - lapMean * lapMean else 0.0
    val blurScore = max(0.0, lapVar)

    return Triple(blurScore, mean, std)
}

private fun cropInPlaceToCenterSquare(file: File, maxOutputSize: Int): Boolean {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    val srcW = options.outWidth
    val srcH = options.outHeight
    if (srcW <= 0 || srcH <= 0) return false

    var sample = 1
    val maxSide = max(srcW, srcH)
    while (maxSide / sample > maxOutputSize) {
        sample *= 2
    }

    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = max(1, sample) }
    val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return false

    val oriented = try {
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
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
        if (!matrix.isIdentity) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } else {
            decoded
        }
    } catch (_: Exception) {
        decoded
    }

    val side = min(oriented.width, oriented.height)
    val left = (oriented.width - side) / 2
    val top = (oriented.height - side) / 2
    val cropped = try {
        Bitmap.createBitmap(oriented, left, top, side, side)
    } catch (_: Exception) {
        return false
    }

    return try {
        FileOutputStream(file).use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        try {
            val outExif = ExifInterface(file.absolutePath)
            outExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            outExif.saveAttributes()
        } catch (_: Exception) {
        }
        true
    } catch (_: Exception) {
        false
    }
}

private fun createVisionInputFile(sourceFile: File, maxOutputSize: Int): File? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(sourceFile.absolutePath, options)
    val srcW = options.outWidth
    val srcH = options.outHeight
    if (srcW <= 0 || srcH <= 0) return null

    var sample = 1
    val maxSide = max(srcW, srcH)
    while (maxSide / sample > maxOutputSize) {
        sample *= 2
    }

    val decoded = BitmapFactory.decodeFile(
        sourceFile.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = max(1, sample) }
    ) ?: return null

    val bitmap = if (decoded.width > maxOutputSize || decoded.height > maxOutputSize) {
        val side = max(decoded.width, decoded.height)
        val scale = maxOutputSize.toFloat() / side.toFloat()
        val targetW = max(1, (decoded.width * scale).toInt())
        val targetH = max(1, (decoded.height * scale).toInt())
        Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
    } else {
        decoded
    }

    val outFile = File(sourceFile.parentFile ?: sourceFile.parent?.let { File(it) } ?: return null, "vision_${UUID.randomUUID()}.jpg")
    return try {
        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        }
        outFile
    } catch (_: Exception) {
        try {
            outFile.delete()
        } catch (_: Exception) {
        }
        null
    }
}

private fun persistCapturedPhoto(context: Context, sourceFile: File, angleLabel: String): File? {
    val dir = File(context.filesDir, "durian_photos").apply { mkdirs() }
    val safeAngle = angleLabel.replace(Regex("[^A-Za-z0-9_\\u4e00-\\u9fa5-]"), "_")
    val dest = File(dir, "photo_${safeAngle}_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
    return runCatching {
        sourceFile.copyTo(dest, overwrite = true)
        if (sourceFile.parentFile == context.cacheDir && sourceFile.absolutePath != dest.absolutePath) {
            runCatching { sourceFile.delete() }
        }
        dest
    }.getOrNull()
}

private fun copyUriToFile(context: Context, uri: Uri, outFile: File): Boolean {
    return try {
        val resolver = context.contentResolver
        val input: InputStream = resolver.openInputStream(uri) ?: return false
        input.use { inStream ->
            FileOutputStream(outFile).use { out ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val read = inStream.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                }
            }
        }
        true
    } catch (_: Exception) {
        try {
            outFile.delete()
        } catch (_: Exception) {
        }
        false
    }
}
