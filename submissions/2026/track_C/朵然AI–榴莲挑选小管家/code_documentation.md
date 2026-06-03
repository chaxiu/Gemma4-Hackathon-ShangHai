# Doran AI 代码文档


## 0. 核心代码索引

下面这些文件覆盖本项目最关键的 Gemma 4 调用、Native Function Calling、多模态和端侧部署逻辑：

| 能力 | 源码文件 | 关键实现 |
|---|---|---|
| Gemma 4 / LiteRT-LM 调用 | `data/remote/llm/LlmRepository.kt` | `EngineConfig`、`Engine.initialize()`、`createConversation()`、`sendMessage()` |
| 文本推理 | `LlmRepository.getChatCompletion()` | `Contents.of(userPrompt)` |
| 图片多模态 | `LlmRepository.getVisionCompletion()` | `Content.ImageFile(imagePath)` + `Content.Text(userPrompt)` |
| 音频多模态 | `LlmRepository.getAudioCompletion()`、`AudioLlmService.kt` | `Content.AudioFile(audioPath)`，独立进程 `:audio_lm` |
| Native Function Calling | `data/remote/agent/DoranAdkAgent.kt` | `FunctionTool`、`FunctionDeclaration`、`FunctionCall` |
| P0-P5 工具链 | `data/remote/agent/DoranAdkAnalysisWorkflow.kt` | ADK 工具按顺序调用并输出 trace |
| Edge 部署 | `ui/screens/nativeui/ModelManagerScreen.kt`、`sendModel.sh` | 本地导入、URL 下载、ADB push、CPU/GPU 回退 |

## 1. 工程入口

- `settings.gradle.kts`：项目模块配置。
- `app/build.gradle.kts`：Android 应用依赖和构建配置。
- `gradle/libs.versions.toml`：版本目录。
- `app/src/main/AndroidManifest.xml`：权限、Activity、Service、Widget、悬浮球和 LiteRT-LM native library 声明。
- `app/src/main/java/com/winter/durianai/MainActivity.kt`：主入口，安装 Splash、设置语言、主题、导航和悬浮球启动。

关键依赖：

- Kotlin 2.2.10
- Android Gradle Plugin 9.1.1
- Jetpack Compose + Material 3
- Navigation Compose
- CameraX
- DataStore
- WorkManager
- Google ADK Kotlin
- LiteRT-LM Android
- OkHttp

## 2. 目录结构

```text
app/src/main/java/com/winter/durianai/
├── MainActivity.kt
├── data/
│   ├── local/
│   │   ├── prefs/UserPreferencesRepository.kt
│   │   └── session/ChatSessionStore.kt
│   └── remote/
│       ├── agent/DoranAdkAgent.kt
│       ├── agent/DoranAdkAnalysisWorkflow.kt
│       └── llm/
│           ├── LlmRepository.kt
│           ├── AudioLlmClient.kt
│           └── AudioLlmService.kt
├── domain/model/
│   ├── DurianParameters.kt
│   ├── DurianTaskState.kt
│   └── DurianVarietyProfile.kt
├── floating/
├── ui/
│   ├── AppViewModel.kt
│   ├── navigation/AppNavigation.kt
│   ├── screens/agent/
│   ├── screens/camera/
│   └── screens/nativeui/
└── widgets/
```

## 3. 应用导航

文件：`ui/navigation/AppNavigation.kt`

使用 `ModalNavigationDrawer` 和 `NavHost` 组织页面：

- `dashboard`：首页。
- `agent_chat`：核心 Agent 对话页。
- `camera_capture`：五角度拍摄页。
- `history`：历史会话。
- `report_detail/{sessionId}`：报告详情。
- `profile`：徽章/成就。
- `stats`：统计页。
- `model_manager`：模型管理。
- `widgets`：桌面组件管理。
- `settings`：设置（支持多语言，自动夜间模式）。
- `about`：关于页面。

所有核心业务共享同一个 `AgentChatViewModel`，保证对话、拍照、报告、历史之间状态一致。

## 4. 领域模型

### 4.1 榴莲参数

文件：`domain/model/DurianParameters.kt`

```kotlin
data class DurianParameters(
    val weightKg: Float? = null,
    val largeLobes: Int = 0,
    val smallLobes: Int = 0,
    val shape: DurianShape? = null,
    val variety: DurianVariety? = null
)
```

`isComplete()` 要求：

- 重量存在。
- 大房或小房至少一个大于 0。
- 形态存在。

品种可选，`AUTO` 或 `null` 会走通用先验。

### 4.2 任务状态

文件：`domain/model/DurianTaskState.kt`

核心结构：

- `PhotoSet`：照片路径、状态、无效原因、质检结果。
- `AnalysisTaskSnapshot`：分析状态、分数、等级、阶段、最新报告。
- `AnalysisReport`：最终报告、参数快照、图片、trace、中间结果、建议。
- `DurianTaskPatch`：局部更新任务状态。
- `DurianTaskEvent`：参数、照片、分析变化事件。

### 4.3 品种先验

文件：`domain/model/DurianVarietyProfile.kt`

`DurianVarietyProfiles` 维护金枕、猫山王、黑刺、托曼尼、D24 和通用榴莲的先验信息，包括：

- 典型重量范围
- 理想重量范围
- 可食率范围
- 理想大房数量
- 容许小房数量
- 偏好形态
- 壳厚倾向
- 刺密度倾向
- 成熟提示和风险提示
- 分数偏置

## 5. 本地模型层

### 5.1 LlmRepository

文件：`data/remote/llm/LlmRepository.kt`

职责：

- 查找模型文件。
- 初始化 LiteRT-LM `Engine`。
- 选择 CPU/GPU 后端。
- OpenCL/GPU 插件检测。
- 初始化和推理超时处理。
- GPU 失败后 CPU 回退。
- 文本、视觉、音频推理统一封装。
- 记录 LLM 调用日志。

主要接口：

```kotlin
suspend fun initializeEngine(): Boolean
suspend fun getChatCompletion(systemPrompt: String, userPrompt: String): String
suspend fun getVisionCompletion(systemPrompt: String, userPrompt: String, imagePath: String): String
suspend fun getAudioCompletion(systemPrompt: String, userPrompt: String, audioPath: String): String
fun findModelFile(): File?
fun getGpuCapability(): GpuCapability
```

Gemma 4 / LiteRT-LM 初始化代码位于 `initializeEngineInternal()`：

```kotlin
val engineConfig = EngineConfig(
    modelPath = file.absolutePath,
    backend = backend,
    visionBackend = if (label == "gpu") Backend.CPU() else backend,
    cacheDir = context.cacheDir.path
)
Engine(engineConfig).apply { initialize() }
```

文本推理核心：

```kotlin
conversation.sendMessage(Contents.of(userPrompt))
```

图片多模态核心：

```kotlin
conversation.sendMessage(
    Contents.of(Content.ImageFile(imagePath), Content.Text(userPrompt))
)
```

音频多模态核心：

```kotlin
conversation.sendMessage(
    Contents.of(Content.AudioFile(audioPath), Content.Text(userPrompt))
)
```

模型查找优先级：

1. DataStore 中的 `activeModelPath`。
2. `externalFilesDir/models/doran.litertlm`。
3. `externalFilesDir/models/*.litertlm`。
4. 兼容旧路径 `externalFilesDir/doran.litertlm` 或同目录其他 `.litertlm`。

### 5.2 AudioLlmService

文件：

- `data/remote/llm/AudioLlmClient.kt`
- `data/remote/llm/AudioLlmService.kt`

音频推理通过 Android Service 放到独立进程 `:audio_lm`，使用 `ResultReceiver` 回传结果。这样可以隔离音频模型初始化和推理对主进程的影响。

## 6. ADK Agent 层

### 6.1 DoranAdkAgent

文件：`data/remote/agent/DoranAdkAgent.kt`

这是用户对话 Agent。它把 `LlmRepository` 包装成 ADK `Model`，再用 `LlmAgent + InMemoryRunner` 执行一轮对话。

Native Function Calling 暴露的 App 原生工具包括：

- `update_durian_parameters`
- `request_input_form`
- `request_camera_capture`
- `start_durian_analysis`
- `restart_selection`

输出结构：

```kotlin
data class DoranAdkRunResult(
    val reply: String,
    val actions: List<DoranAdkAction>,
    val rawModelText: String?
)
```

模型必须输出 JSON：

```json
{"tool_call":{"name":"start_durian_analysis","args":{}},"reply":"收到，我来检查照片和参数。"}
```

或：

```json
{"reply":"自然语言回答"}
```

App 解析 JSON 后转换为 ADK `FunctionCall`，再由工具修改 `DoranAgentTurnState`，最后返回 `DoranAdkAction` 给 ViewModel。这样阅读源码时可以同时看到 ADK 原生函数声明、工具调用执行和端侧模型适配逻辑。

### 6.2 DoranAdkAnalysisWorkflow

文件：`data/remote/agent/DoranAdkAnalysisWorkflow.kt`

这是 P0-P5 分析工作流。当前为 Demo 模拟/确定性代理计算，但使用真实 ADK FunctionTool 形式组织。

事件类型：

- `StepStarted`
- `StepCompleted`
- `FinalReport`

步骤：

| 阶段 | Tool | 说明 |
|---|---|---|
| P0 | `run_quality_gate` | 检查照片数量、角度覆盖、质检风险和参数完整性 |
| P1 | `run_segmentation` | Demo 分割与归一化代理 |
| P2 | `run_spike_features` | Demo 刺密度、方向一致性、高度代理 |
| P3 | `run_shape_geometry` | Demo 对称性、壳厚代理、体型 |
| P4 | `run_variety_priors` | 品种先验融合 |
| P5 | `run_fusion_report` | 计算 score/level 并生成解释 |

后续替换真实 CV 时，优先替换各 Tool 的 `execute()` 内部逻辑，保留事件、trace、UI 进度和报告结构。

## 7. 核心 ViewModel

文件：`ui/screens/agent/AgentChatViewModel.kt`

这是业务状态中心，负责：

- 管理多会话。
- 维护当前参数、照片、分析任务。
- 初始化本地模型。
- 调用 ADK Agent。
- 处理 Agent action。
- 插入动态 UI 消息。
- 处理拍照完成/照片无效。
- 调用本地视觉模型做照片质检。
- 启动 P0-P5 分析工作流。
- 生成报告、建议、徽章、统计和桌面组件更新。

关键状态：

- `_sessions`
- `_messages`
- `_currentParamsFlow`
- `_currentTaskState`
- `_isModelLoading`
- `_isEngineReady`
- `_isThinking`
- `_isReportAnalyzing`
- `_analysisStage`

关键方法：

- `maybeInitEngine()`
- `runModelHealthCheck()`
- `applyLlmBackend()`
- `onVisionCaptureFinished()`
- `onVisionCaptureInvalid()`
- `checkPhotoWithDoran()`
- `requestAnalysisFromDock()`
- `cancelPhotoDrivenAnalysis()`
- `startPhotoDrivenAnalysisSimulation()`

注意：`startPhotoDrivenAnalysisSimulation()` 名称保留了 Demo 语义，内部实际调用 `DoranAdkAnalysisWorkflow`，并不是传统随机模拟。

## 8. Agent 对话 UI

文件：

- `ui/screens/agent/AgentChatScreen.kt`
- `ui/screens/agent/models/ChatMessage.kt`
- `ui/screens/agent/components/`
- `ui/screens/agent/components/widgets/`

`ChatMessage` 是动态渲染核心。消息类型包括：

- `TextMessage`
- `ImageStripMessage`
- `AudioMessage`
- `CameraWidgetMessage`
- `InputFormWidgetMessage`
- `ResultReportMessage`
- `BadgeUnlockedMessage`
- `AnalysisProgressMessage`
- `ActionMessage`
- `DevLogMessage`

`AgentChatScreen` 通过 `LazyColumn` 按类型渲染消息。模型未准备时会显示提示卡，允许用户跳转模型管理页。顶部栏可切换会话、创建新任务、查看模型状态和刷新模型。

## 9. 相机与照片质检

文件：`ui/screens/camera/CameraCaptureScreen.kt`

功能：

- CameraX 实时预览。
- 后置/前置镜头切换。
- 五角度步骤引导。
- 相册图片选择。
- 裁剪/压缩到可分析尺寸。
- 快速质量检查：模糊、亮度、对比度等。
- 调用 `AgentChatViewModel.checkPhotoWithDoran()` 做本地视觉质检。
- 记录 `PhotoQualityProfile`。
- 可强制使用有风险照片。

照片进入分析前需要 `PhotoSetStatus.Ready`。无效照片会设置为 `Invalid` 并提示重拍。

## 10. 模型管理页

文件：`ui/screens/nativeui/ModelManagerScreen.kt`

功能：

- 扫描 App 外部私有目录中的 `.litertlm`。
- 本地文件导入。
- URL 下载。
- 当前模型切换。
- 模型删除。
- 基础兼容性判断：后缀、文件大小、文件名是否疑似视觉模型。
- 健康检查。
- 开发者模式下 CPU/GPU 后端选择与 OpenCL 诊断。

`sendModel.sh` 可用于 Demo 阶段通过 ADB 推送：

```bash
adb shell "mkdir -p /sdcard/Android/data/com.winter.durianai/files/models"
adb push ./gemma-4-e2b-it.litertlm /sdcard/Android/data/com.winter.durianai/files/models/gemma-4-e2b-it.litertlm
```

## 11. 持久化

### 11.1 UserPreferencesRepository

文件：`data/local/prefs/UserPreferencesRepository.kt`

使用 DataStore 保存：

- 主题、语言、开发者模式。
- LLM temperature/topP/topK/backend。
- active model path。
- 报告统计。
- Widget 内容。
- 已通知徽章 ID。

### 11.2 ChatSessionStore

文件：`data/local/session/ChatSessionStore.kt`

保存位置：`filesDir/doran_sessions.json`，并维护 `.bak` 备份。

保存内容：

- 会话 ID、标题、头像路径。
- 参数状态。
- 当前任务状态。
- 消息列表。
- 照片质量结果。
- 最新分析报告、trace、建议。

## 12. 桌面组件与辅助入口

目录：`widgets/`

已实现：

- 每日建议组件。
- 最新报告/徽章组件。
- 单徽章组件。
- Widget pin 结果接收。
- WorkManager 周期更新。

目录：`floating/`

已实现：

- 悬浮球 Service。
- 快捷设置 Tile。
- 悬浮球控制器和回收目标。

这些功能不是 AI 核心，但能展示产品完整度和现场使用便利性。

## 13. 当前 Demo 边界

已实现：

- 本地模型管理与 LiteRT-LM 初始化。
- 文本/视觉/音频接口。
- ADK Agent 工具调用。
- Agent-driven UI。
- CameraX 五角度采集。
- 本地视觉照片质检。
- P0-P5 ADK 工具链和进度 UI。
- 会话、报告、统计、徽章、Widget。

Demo/待替换：

- P1 分割、P2 刺特征、P3 几何分析当前是代理值，不是真实 CV 测量。
- P5 评分目前主要基于参数、品种先验和照片质检风险。
- 视觉检查依赖导入模型是否支持图像输入。

建议后续开发优先级：

1. 把 P0 照片质检结果导出成可复现样本。
2. 接入真实主体分割或轻量分割模型。
3. 实现刺密度/方向/轮廓粗糙度的端上特征提取。
4. 用真实开果结果校准评分权重。
5. 将 P0-P5 trace 导出为比赛技术附录或评测日志。

## 14. 构建与运行

常规构建：

```bash
./gradlew assembleDebug
```

安装到设备：

```bash
./gradlew installDebug
```

模型准备方式：

- 在 App 的模型管理页选择本地 `.litertlm`。
- 在 App 的模型管理页输入 URL 下载。
- Demo 阶段执行 `sendModel.sh` 通过 ADB push。

运行注意：

- 首次进入对话页会初始化本地模型，可能耗时较长(需要已经准备好模型，或者Push到设备端）。
- 模型文件过小、后缀错误或不支持 LiteRT-LM 会导致健康检查失败。
- 模拟器通常缺少 OpenCL，GPU 后端可能不可用，Doran调试阶段CPU 是最稳妥选择。
