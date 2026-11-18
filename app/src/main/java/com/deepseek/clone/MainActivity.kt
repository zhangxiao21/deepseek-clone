package com.deepseek.clone

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import com.deepseek.clone.data.local.SecureStore
import com.deepseek.clone.data.model.ContentPart
import com.deepseek.clone.ui.chat.ChatScreen
import com.deepseek.clone.ui.chat.ChatViewModel
import com.deepseek.clone.ui.login.LoginScreen
import com.deepseek.clone.ui.sessions.SessionsScreen
import com.deepseek.clone.ui.settings.SettingsScreen
import com.deepseek.clone.ui.theme.DeepSeekTheme
import com.deepseek.clone.util.FileHelper
import com.deepseek.clone.util.SpeechHelper
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var speechHelper: SpeechHelper
    private var currentVoiceCallback: ((String) -> Unit)? = null
    private var onFileUploaded: ((ContentPart) -> Unit)? = null
    private var chatViewModelRef: ChatViewModel? = null

    private val requestAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) currentVoiceCallback?.let { startListening(it) }
        else Toast.makeText(this, "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
    }

    // 图片选择：走 vision，purpose=user_data
    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { uploadFile(it, isImage = true) } }

    // 文件选择：文档走 rich_layout_recognition
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { uploadFile(it, isImage = false) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val services = (application as DeepSeekApp).services
        speechHelper = SpeechHelper(this)

        setContent {
            DeepSeekTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation(
                        application = application,
                        store = services.store,
                        repository = services.repository,
                        zipManager = services.zipManager,
                        onAttachFile = { filePicker.launch("*/*") },
                        onPickImage = { imagePicker.launch("image/*") },
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

    /** 用官方 Files API 上传，成功后把 ContentPart 加入待发送附件 */
    private fun uploadFile(uri: Uri, isImage: Boolean) {
        Toast.makeText(this, "正在上传…", Toast.LENGTH_SHORT).show()
        Thread {
            val file = FileHelper.copyUriToCache(this, uri)
            if (file == null) {
                runOnUiThread { Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            val repo = (application as DeepSeekApp).services.repository
            val purpose = repo.purposeFor(file)
            val result = kotlinx.coroutines.runBlocking { repo.uploadFile(file, purpose) }
            runOnUiThread {
                result.fold(
                    onSuccess = { fileId ->
                        val part = if (isImage) {
                            ContentPart(type = "file", fileId = fileId, filename = file.name)
                        } else {
                            ContentPart(type = "file", fileId = fileId, filename = file.name)
                        }
                        chatViewModelRef?.addPendingPart(part)
                        onFileUploaded?.invoke(part)
                        Toast.makeText(this, "已添加：${file.name}", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { e ->
                        Toast.makeText(this, "上传失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }.start()
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
    onAttachFile: () -> Unit,
    onPickImage: () -> Unit,
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
                    onVoiceInput = onVoiceInput
                )
            }
        }
        Screen.Settings -> {
            SettingsScreen(
                store = store,
                zipManager = zipManager,
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
