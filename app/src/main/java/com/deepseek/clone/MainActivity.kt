package com.deepseek.clone

import android.Manifest
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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

    private val requestAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) currentVoiceCallback?.let { startListening(it) }
        else Toast.makeText(this, "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
    }

    // 图片选择：走 vision，purpose=user_data（按文件扩展名自动判断）
    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { uploadFile(it) } }

    // 文件选择：文档走 rich_layout_recognition
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
            DeepSeekTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation(
                        application = application,
                        store = services.store,
                        repository = services.repository,
                        zipManager = services.zipManager,
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            services.store.themeMode = mode
                            themeMode = mode
                            AppColors.applyDark(ThemeMode.isDark(mode, systemDark))
                        },
                        onAttachFile = { filePicker.launch("*/*") },
                        onPickImage = { imagePicker.launch("image/*") },
                        onTakePhoto = { launchCamera() },
                        onVoiceInput = { cb -> requestVoice(cb) },
                        onViewModelReady = { chatViewModelRef = it }
                    )
                }
            }
        }
    }

    private fun requestVoice(callback: (String) -> Unit) {
        currentVoiceCallback = callback
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListening(callback)
        } else {
            requestAudio.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening(callback: (String) -> Unit) {
        speechHelper.startListening(
            onResult = callback,
            onError = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        )
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

    /** 拍照直传：已持有本地 File，无需再经 content:// 复制，保证 .jpg 扩展名与正确 MIME */
    private fun uploadCameraFile(file: File) {
        Thread { uploadFileInternal(file) }.start()
    }

    /**
     * 用官方 Files API 上传，成功后把 ContentPart 加入待发送附件。
     *
     * 关键（HTTP 400 根因）：引用已上传文件时，content part 只能带 type + file_id，
     * 绝不能同时带 filename —— 官方 API 报
     * "file cannot have filename when file_id is present"。
     * 文件名只用于本地 UI 展示，通过 addPendingPart(part, name) 单独传入，不进请求体。
     */
    private fun uploadFile(uri: Uri) {
        Thread {
            val file = FileHelper.copyUriToCache(this, uri)
            if (file == null || !file.exists()) {
                runOnUiThread { Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            uploadFileInternal(file)
        }.start()
    }

    private fun uploadFileInternal(file: File) {
        runOnUiThread { Toast.makeText(this, "正在上传…", Toast.LENGTH_SHORT).show() }
        val repo = (application as DeepSeekApp).services.repository
        val purpose = repo.purposeFor(file)
        val result = kotlinx.coroutines.runBlocking { repo.uploadFile(file, purpose) }
        runOnUiThread {
            result.fold(
                onSuccess = { fileId ->
                    val part = ContentPart(type = "file", fileId = fileId)
                    chatViewModelRef?.addPendingPart(part, file.name)
                    Toast.makeText(this, "已添加：${file.name}", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    Toast.makeText(this, "上传失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechHelper.destroy()
    }
}

/** 内部导航：sealed 状态机 */
@Composable
private fun AppNavigation(
    application: android.app.Application,
    store: SecureStore,
    repository: com.deepseek.clone.data.repository.Repository,
    zipManager: com.deepseek.clone.data.importer.ChatZipManager,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onAttachFile: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onVoiceInput: ((String) -> Unit) -> Unit,
    onViewModelReady: (ChatViewModel) -> Unit
) {
    var loggedIn by remember { mutableStateOf(store.isLoggedIn) }
    var screen by remember { mutableStateOf<Screen>(Screen.Sessions) }
    var chatSessionId by remember { mutableStateOf<String?>(null) }
    var chatTitle by remember { mutableStateOf("新对话") }
    val scope = rememberCoroutineScope()
    val chatViewModel = remember { ChatViewModel(application) }

    LaunchedEffect(chatViewModel) { onViewModelReady(chatViewModel) }

    // 显示鉴权错误（API Key 失效/余额不足）
    val app = application as DeepSeekApp
    app.authErrorMessage?.let { msg ->
       LaunchedEffect(msg) {
            Toast.makeText(application, msg, Toast.LENGTH_LONG).show()
            app.authErrorMessage = null
            loggedIn = false
            screen = Screen.Sessions
        }
    }

    if (!loggedIn) {
        LoginScreen(
            store = store,
            repository = repository,
            onLoginSuccess = {
                loggedIn = true
                screen = Screen.Sessions
            }
        )
        return
    }

    when (screen) {
        Screen.Sessions -> {
            SessionsScreen(
                repository = repository,
                onOpenChat = { id, title ->
                    chatSessionId = id
                    chatTitle = title
                    screen = Screen.Chat
                },
                onNewChat = {
                    scope.launch {
                        val id = repository.createLocalSession("新对话", store.defaultModelType)
                        chatSessionId = id
                        chatTitle = "新对话"
                        screen = Screen.Chat
                    }
                },
                onSettings = { screen = Screen.Settings }
            )
        }
        Screen.Chat -> {
            chatSessionId?.let { id ->
                ChatScreen(
                    sessionId = id,
                    sessionTitle = chatTitle,
                    viewModel = chatViewModel,
                    onBack = { screen = Screen.Sessions },
                    onAttachFile = onAttachFile,
                    onPickImage = onPickImage,
                    onTakePhoto = onTakePhoto,
                    onVoiceInput = onVoiceInput
                )
            }
        }
        Screen.Settings -> {
            SettingsScreen(
                store = store,
                zipManager = zipManager,
                repository = repository,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                onBack = { screen = Screen.Sessions },
                onLogout = {
                    repository.clearApiKey()
                    loggedIn = false
                    screen = Screen.Sessions
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
