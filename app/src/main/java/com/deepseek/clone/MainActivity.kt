package com.deepseek.clone

import android.Manifest
import android.app.Activity
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.deepseek.clone.data.local.SecureStore
import com.deepseek.clone.data.model.ContentPart
import com.deepseek.clone.ui.chat.ChatScreen
import com.deepseek.clone.ui.chat.ChatViewModel
import com.deepseek.clone.ui.login.LoginScreen
import com.deepseek.clone.ui.picker.PhotoPickerScreen
import com.deepseek.clone.ui.sessions.SessionsScreen
import com.deepseek.clone.ui.settings.SettingsScreen
import com.deepseek.clone.ui.theme.AppColors
import com.deepseek.clone.ui.theme.DeepSeekTheme
import com.deepseek.clone.ui.theme.ThemeMode
import com.deepseek.clone.util.FileHelper
import com.deepseek.clone.util.SpeechHelper
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var speechHelper: SpeechHelper
    private var currentVoiceCallback: ((String) -> Unit)? = null
    private var chatViewModelRef: ChatViewModel? = null
    private var cameraFile: File? = null
    private var voiceListening by mutableStateOf(false)

    private val requestAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) currentVoiceCallback?.let { startListening(it) }
        else Toast.makeText(this, "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
    }

    /** 部分国产 ROM 没有 SpeechRecognizer 服务，降级到系统语音识别界面 */
    private val voiceFallback = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        voiceListening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = matches?.firstOrNull()
            if (!text.isNullOrBlank()) currentVoiceCallback?.invoke(text)
            else Toast.makeText(this, "没有识别到内容", Toast.LENGTH_SHORT).show()
        }
    }

    // 文档仍走系统文件选择（用户只对图片选择器的观感有要求）
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { uploadFile(it) } }

    // 拍照：输出到 FileProvider 授权的缓存文件，拍完走与图片相同的上传链路
    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) cameraFile?.let { uploadCameraFile(it) }
        cameraFile = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val services = (application as DeepSeekApp).services
        speechHelper = SpeechHelper(this)

        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        // 首帧之前同步调色板，避免深色模式下闪一下白屏
        AppColors.applyDark(ThemeMode.isDark(services.store.themeMode, systemDark))

        setContent {
            var themeMode by remember { mutableStateOf(services.store.themeMode) }
            var fontScale by remember { mutableStateOf(services.store.fontScale) }
            DeepSeekTheme(themeMode = themeMode, fontScale = fontScale) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation(
                        application = application,
                        store = services.store,
                        repository = services.repository,
                        zipManager = services.zipManager,
                        themeMode = themeMode,
                        fontScale = fontScale,
                        onFontScaleChange = { scale ->
                            services.store.fontScale = scale
                            fontScale = scale
                        },
                        onThemeModeChange = { mode ->
                            services.store.themeMode = mode
                            themeMode = mode
                            AppColors.applyDark(ThemeMode.isDark(mode, systemDark))
                        },
                        onAttachFile = { filePicker.launch("*/*") },
                        onImagesSelected = { uris -> uploadFiles(uris) },
                        onTakePhoto = { launchCamera() },
                        onVoiceInput = { cb -> requestVoice(cb) },
                        onVoiceStop = { stopVoice() },
                        voiceListening = voiceListening,
                        onViewModelReady = { chatViewModelRef = it }
                    )
                }
            }
        }
    }

    // ---------- 语音输入 ----------

    private fun requestVoice(callback: (String) -> Unit) {
        if (voiceListening) {
            stopVoice()
            return
        }
        currentVoiceCallback = callback
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudio.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startListening(callback)
        }
    }

    /** 拉起系统/厂商语音识别界面（国内 ROM 上比 Google 在线服务可靠得多） */
    private fun launchSystemVoice() {
        try {
            voiceFallback.launch(speechHelper.buildIntent())
            voiceListening = true
        } catch (e: Exception) {
            voiceListening = false
            Toast.makeText(
                this,
                "此设备没有可用的语音识别服务，可试试输入法的麦克风或安装离线语音包",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startListening(callback: (String) -> Unit) {
        // 没有设备端离线识别包时，直接用系统语音界面，跳过必然报 ERROR_NETWORK 的在线服务
        if (!speechHelper.hasOnDeviceRecognition()) {
            launchSystemVoice()
            return
        }
        speechHelper.startListening(
            onResult = { text ->
                voiceListening = false
                callback(text)
            },
            onError = { msg ->
                voiceListening = false
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            },
            onState = { listening -> voiceListening = listening },
            onNeedFallback = { launchSystemVoice() }
        )
    }

    private fun stopVoice() {
        if (speechHelper.hasOnDeviceRecognition()) speechHelper.stopListening()
        else voiceListening = false
    }

    /** 启动系统相机拍照（无需 CAMERA 权限，走 ACTION_IMAGE_CAPTURE 的 TakePicture 契约） */
    private fun launchCamera() {
        try {
            val photo = File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photo)
            cameraFile = photo
            takePicture.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "无法启动相机：${e.message}", Toast.LENGTH_SHORT).show()
            cameraFile = null
        }
    }

    // ---------- 文件上传 ----------

    private fun uploadCameraFile(file: File) {
        Thread { uploadFilesInternal(listOf(file)) }.start()
    }

    private fun uploadFile(uri: Uri) {
        Thread {
            val file = FileHelper.copyUriToCache(this, uri)
            if (file == null) {
                runOnUiThread { Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show() }
            } else {
                uploadFilesInternal(listOf(file))
            }
        }.start()
    }

    /** 应用内相册多选后批量上传：一次复制，逐个走 Files API */
    private fun uploadFiles(uris: List<Uri>) {
        Thread {
            val files = uris.mapIndexedNotNull { index, uri ->
                FileHelper.copyUriToCache(this, uri, tag = "$index", defaultExt = ".jpg")
            }
            if (files.isEmpty()) {
                runOnUiThread { Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show() }
            } else {
                uploadFilesInternal(files)
            }
        }.start()
    }

    /**
     * 用官方 Files API 上传，成功后把 ContentPart 加入待发送附件。
     *
     * 关键（HTTP 400 根因）：引用已上传文件时，content part 只能带 type + file_id，
     * 绝不能同时带 filename —— 官方 API 报
     * "file cannot have filename when file_id is present"。
     * 文件名只用于本地 UI 展示，通过 addPendingPart(part, name) 单独传入，不进请求体。
     */
    private fun uploadFilesInternal(files: List<File>) {
        if (files.isEmpty()) return
        runOnUiThread {
            Toast.makeText(this, "正在上传 ${files.size} 个文件…", Toast.LENGTH_SHORT).show()
        }
        val repo = (application as DeepSeekApp).services.repository
        var ok = 0
        var firstError: String? = null
        files.forEach { file ->
            val purpose = repo.purposeFor(file)
            val result = kotlinx.coroutines.runBlocking { repo.uploadFile(file, purpose) }
            result.fold(
                onSuccess = { fileId ->
                    val part = ContentPart(type = "file", fileId = fileId)
                    // 本地缓存文件路径只用于 UI 缩略图预览，绝不进入 API 请求体
                    runOnUiThread { chatViewModelRef?.addPendingPart(part, file.name, file.absolutePath) }
                    ok++
                },
                onFailure = { e ->
                    if (firstError == null) firstError = "${file.name}: ${e.message}"
                }
            )
        }
        runOnUiThread {
            val msg = when {
                firstError == null -> "已添加 $ok 个附件"
                ok > 0 -> "已添加 $ok 个，失败：${files.size - ok} 个（$firstError）"
                else -> "上传失败：$firstError"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechHelper.destroy()
    }
}

/** 内部导航：sealed 状态机 + 图片选择器浮层 */
@Composable
private fun AppNavigation(
    application: android.app.Application,
    store: SecureStore,
    repository: com.deepseek.clone.data.repository.Repository,
    zipManager: com.deepseek.clone.data.importer.ChatZipManager,
    themeMode: String,
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit,
    onThemeModeChange: (String) -> Unit,
    onAttachFile: () -> Unit,
    onImagesSelected: (List<Uri>) -> Unit,
    onTakePhoto: () -> Unit,
    onVoiceInput: ((String) -> Unit) -> Unit,
    onVoiceStop: () -> Unit,
    voiceListening: Boolean,
    onViewModelReady: (ChatViewModel) -> Unit
) {
    var loggedIn by remember { mutableStateOf(store.isLoggedIn) }
    // 官方首页：显示欢迎语的空状态，但不落库任何会话；发第一条消息时才创建
    var screen by remember { mutableStateOf<Screen>(Screen.Chat) }
    var chatSessionId by remember { mutableStateOf<String?>(null) }
    var chatTitle by remember { mutableStateOf("新对话") }
    var showPhotoPicker by remember { mutableStateOf(false) }
    val chatViewModel = remember { ChatViewModel(application) }

    LaunchedEffect(chatViewModel) { onViewModelReady(chatViewModel) }

    // 空首页发出第一条消息后，ViewModel 把新建的会话 id 同步回导航
    LaunchedEffect(Unit) {
        chatViewModel.startedSessionId.collect { id ->
            if (id != null) {
                chatSessionId = id
                chatTitle = "新对话"
            }
        }
    }

    // 显示鉴权错误（API Key 失效/余额不足）
    val app = application as DeepSeekApp
    app.authErrorMessage?.let { msg ->
        LaunchedEffect(msg) {
            Toast.makeText(application, msg, Toast.LENGTH_LONG).show()
            app.authErrorMessage = null
            loggedIn = false
            chatSessionId = null
            screen = Screen.Chat
        }
    }

    /** 新对话 = 回到空首页，不预先创建会话记录 */
    fun openNewChat() {
        chatSessionId = null
        chatTitle = "新对话"
        screen = Screen.Chat
    }

    if (!loggedIn) {
        LoginScreen(
            store = store,
            repository = repository,
            onLoginSuccess = {
                loggedIn = true
                chatSessionId = null
                screen = Screen.Chat
            }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (screen) {
            Screen.Sessions -> {
                SessionsScreen(
                    store = store,
                    repository = repository,
                    onOpenChat = { id, title ->
                        chatSessionId = id
                        chatTitle = title
                        screen = Screen.Chat
                    },
                    onNewChat = { openNewChat() },
                    onSettings = { screen = Screen.Settings },
                    onSessionsDeleted = { ids ->
                        if (chatSessionId != null && ids.contains(chatSessionId)) {
                            chatSessionId = null
                            chatTitle = "新对话"
                        }
                    }
                )
            }
            Screen.Chat -> {
                ChatScreen(
                    sessionId = chatSessionId,
                    sessionTitle = chatTitle,
                    viewModel = chatViewModel,
                    onBack = { screen = Screen.Sessions },
                    onNewChat = { openNewChat() },
                    onAttachFile = onAttachFile,
                    onPickImages = { showPhotoPicker = true },
                    onQuickImages = onImagesSelected,
                    onTakePhoto = onTakePhoto,
                    onVoiceInput = onVoiceInput,
                    onVoiceStop = onVoiceStop,
                    voiceListening = voiceListening
                )
            }
            Screen.Settings -> {
                SettingsScreen(
                    store = store,
                    zipManager = zipManager,
                    repository = repository,
                    themeMode = themeMode,
                    fontScale = fontScale,
                    onFontScaleChange = onFontScaleChange,
                    onThemeModeChange = onThemeModeChange,
                    onBack = { screen = Screen.Sessions },
                    onLogout = {
                        repository.clearApiKey()
                        loggedIn = false
                        chatSessionId = null
                        screen = Screen.Chat
                    }
                )
            }
        }

        if (showPhotoPicker) {
            PhotoPickerScreen(
                onCancel = { showPhotoPicker = false },
                onConfirm = { uris ->
                    showPhotoPicker = false
                    onImagesSelected(uris)
                }
            )
        }
    }
}

private sealed class Screen {
    object Sessions : Screen()
    object Chat : Screen()
    object Settings : Screen()
}
