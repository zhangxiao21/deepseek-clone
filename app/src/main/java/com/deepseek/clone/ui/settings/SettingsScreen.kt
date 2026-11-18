package com.deepseek.clone.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deepseek.clone.core.ApiConfig
import com.deepseek.clone.core.ModelOption
import com.deepseek.clone.data.importer.ChatZipManager
import com.deepseek.clone.data.local.ApiKeyEntry
import com.deepseek.clone.data.local.SecureStore
import com.deepseek.clone.data.repository.ContextEngine
import com.deepseek.clone.ui.theme.AppColors
import com.deepseek.clone.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/** 官方设置页：浅灰底 + 白色圆角卡片分组，点进子页保留全部高级功能 */
private enum class Page(val title: String) {
    MAIN("设置"),
    ACCOUNT("账号管理"),
    DATA("数据管理"),
    APPEARANCE("外观"),
    PERSONALIZATION("个性化"),
    ABOUT("关于")
}

private data class RowItem(
    val icon: ImageVector,
    val title: String,
    val value: String = "",
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    store: SecureStore,
    zipManager: ChatZipManager,
    repository: com.deepseek.clone.data.repository.Repository,
    themeMode: String,
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit,
    onThemeModeChange: (String) -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.MAIN) }

    // ---------- 通用弹窗状态 ----------
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // ---------- 多 API Key ----------
    var apiKeys by remember { mutableStateOf(store.apiKeys()) }
    var activeKeyId by remember { mutableStateOf(store.activeApiKey()?.id.orEmpty()) }
    var showAddKey by remember { mutableStateOf(false) }
    var keyMenu by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var editingKey by remember { mutableStateOf<ApiKeyEntry?>(null) }

    // ---------- 记忆 / 上下文 / CoT ----------
    var memoryEnabled by remember { mutableStateOf(store.memoryEnabled) }
    var compressionEnabled by remember { mutableStateOf(store.compressionEnabled) }
    var cotVisible by remember { mutableStateOf(store.cotVisible) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    var memoryFacts by remember { mutableStateOf(repository.context.memoryFacts()) }
    var rebuildingMemory by remember { mutableStateOf(false) }

    // ---------- 导入 / 导出 ----------
    var importing by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            importing = true
            scope.launch {
                val result = zipManager.importFromUri(it)
                importing = false
                notice = result.fold(
                    onSuccess = { r ->
                        "成功导入 ${r.sessions} 个对话，${r.messages} 条消息" +
                            if (r.skipped > 0) "（跳过 ${r.skipped} 条异常消息）" else ""
                    },
                    onFailure = { e -> "导入失败：${e.message}" }
                )
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val result = zipManager.exportAll(it)
                notice = result.fold(
                    { "已导出 $it 个对话" },
                    { "导出失败：${it.message}" }
                )
            }
        }
    }

    val pageBg = AppColors.Surface
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(page.title, style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                },
                navigationIcon = {
                    IconButton(onClick = { if (page == Page.MAIN) onBack() else page = Page.MAIN }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回",
                            tint = AppColors.TextPrimary)
                    }
                },
                actions = { Spacer(Modifier.size(48.dp)) },
                colors = TopAppBarDefaults.smallTopAppBarColors(containerColor = pageBg)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(padding)
                .background(pageBg)
                .verticalScroll(rememberScrollState())
        ) {
            when (page) {
                Page.MAIN -> MainPage(
                    themeLabel = ThemeMode.label(themeMode),
                    fontLabel = fontLabel(fontScale),
                    onAccount = { page = Page.ACCOUNT },
                    onData = { page = Page.DATA },
                    onLanguage = { showLanguageDialog = true },
                    onAppearance = { page = Page.APPEARANCE },
                    onFont = { showFontDialog = true },
                    onPersonalization = { page = Page.PERSONALIZATION },
                    onAbout = { page = Page.ABOUT },
                    onTts = { openTtsSettings(context) },
                    onVoiceSettings = { openVoiceInputSettings(context) },
                    onUpdate = { showUpdateDialog = true },
                    onAgreement = { openUrl(context, "https://platform.deepseek.com/") },
                    onHelp = { openUrl(context, "https://api-docs.deepseek.com/zh-cn/") }
                )
                Page.ACCOUNT -> AccountPage(
                    apiKeys = apiKeys,
                    activeKeyId = activeKeyId,
                    onSwitch = { id ->
                        store.setActiveApiKey(id)
                        activeKeyId = id
                        Toast.makeText(context, "已切换密钥", Toast.LENGTH_SHORT).show()
                    },
                    onAdd = { showAddKey = true },
                    onMenu = { keyMenu = it },
                    onOpenPlatform = { openUrl(context, ApiConfig.API_KEY_URL) },
                    onLogout = { showLogoutDialog = true }
                )
                Page.DATA -> DataPage(
                    importing = importing,
                    notice = notice,
                    onImport = { importLauncher.launch("application/zip") },
                    onExport = {
                        exportLauncher.launch("deepseek_chats_${System.currentTimeMillis()}.zip")
                    }
                )
                Page.APPEARANCE -> AppearancePage(
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    defaultModel = store.defaultModelType,
                    onDefaultModelChange = { store.defaultModelType = it }
                )
                Page.PERSONALIZATION -> PersonalizationPage(
                    memoryEnabled = memoryEnabled,
                    onMemoryEnabled = { memoryEnabled = it; store.memoryEnabled = it },
                    compressionEnabled = compressionEnabled,
                    onCompressionEnabled = { compressionEnabled = it; store.compressionEnabled = it },
                    cotVisible = cotVisible,
                    onCotVisible = { cotVisible = it; store.cotVisible = it },
                    memoryCount = memoryFacts.size,
                    rebuilding = rebuildingMemory,
                    onEditMemory = {
                        memoryFacts = repository.context.memoryFacts()
                        showMemoryDialog = true
                    },
                    onRebuild = {
                        if (!rebuildingMemory) {
                            rebuildingMemory = true
                            scope.launch {
                                val result = repository.rebuildMemoryFromHistory()
                                rebuildingMemory = false
                                memoryFacts = repository.context.memoryFacts()
                                notice = result.fold(
                                    { "提炼完成，当前共 $it 条长期记忆" },
                                    { "提炼失败：${it.message}" }
                                )
                            }
                        }
                    },
                    notice = notice
                )
                Page.ABOUT -> AboutPage(context)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    // ---------------- 弹窗 ----------------

    val keyDialogTarget = if (showAddKey) null else editingKey
    if (showAddKey || keyDialogTarget != null) {
        var label by remember(showAddKey, keyDialogTarget?.id) {
            mutableStateOf(keyDialogTarget?.label ?: "密钥 ${apiKeys.size + 1}")
        }
        var keyText by remember(showAddKey, keyDialogTarget?.id) {
            mutableStateOf(keyDialogTarget?.key ?: "")
        }
        var keyError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAddKey = false; editingKey = null },
            title = { Text(if (keyDialogTarget == null) "添加 API Key" else "编辑 API Key") },
            text = {
                Column {
                    OutlinedTextField(value = label, onValueChange = { label = it },
                        label = { Text("备注名") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = keyText,
                        onValueChange = { keyText = it; keyError = null },
                        label = { Text("API Key (sk-...)") }, singleLine = true,
                        isError = keyError != null, modifier = Modifier.fillMaxWidth())
                    keyError?.let {
                        Text(it, color = AppColors.Error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val k = keyText.trim()
                    if (!k.startsWith("sk-") || k.length < 20) {
                        keyError = "请输入以 sk- 开头的有效 API Key"
                    } else {
                        if (keyDialogTarget == null) store.addApiKey(label, k)
                        else store.updateApiKey(keyDialogTarget.id, label, k)
                        apiKeys = store.apiKeys()
                        activeKeyId = store.activeApiKey()?.id.orEmpty()
                        showAddKey = false
                        editingKey = null
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showAddKey = false; editingKey = null }) { Text("取消") }
            }
        )
    }

    keyMenu?.let { entry ->
        AlertDialog(
            onDismissRequest = { keyMenu = null },
            title = { Text(entry.label) },
            text = { Text(entry.masked, color = AppColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { editingKey = entry; keyMenu = null }) { Text("编辑") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        store.removeApiKey(entry.id)
                        apiKeys = store.apiKeys()
                        activeKeyId = store.activeApiKey()?.id.orEmpty()
                        keyMenu = null
                    }) { Text("删除", color = AppColors.Error) }
                    TextButton(onClick = { keyMenu = null }) { Text("取消") }
                }
            }
        )
    }

    if (showMemoryDialog) {
        var text by remember { mutableStateOf(memoryFacts.joinToString("\n")) }
        AlertDialog(
            onDismissRequest = { showMemoryDialog = false },
            title = { Text("长期记忆") },
            text = {
                Column {
                    Text("每行一条。对话结束后自动补充，也可以手动编辑或清空。",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = text, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                        maxLines = 10)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    repository.context.setMemoryFacts(
                        text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    )
                    memoryFacts = repository.context.memoryFacts()
                    showMemoryDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        repository.context.clearMemory()
                        memoryFacts = emptyList()
                        notice = "已清空长期记忆"
                        showMemoryDialog = false
                    }) { Text("清空", color = AppColors.Error) }
                    TextButton(onClick = { showMemoryDialog = false }) { Text("取消") }
                }
            }
        )
    }

    if (showFontDialog) {
        AlertDialog(
            onDismissRequest = { showFontDialog = false },
            title = { Text("字体大小") },
            text = {
                Column {
                    listOf(1.0f to "标准", 1.1f to "大", 1.2f to "超大").forEach { (scale, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onFontScaleChange(scale); showFontDialog = false
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = fontScale == scale, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFontDialog = false }) { Text("关闭") }
            }
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text("语言") },
            text = { Text("当前仅提供「中文（简体中文）」。") },
            confirmButton = { TextButton(onClick = { showLanguageDialog = false }) { Text("知道了") } }
        )
    }

    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("检查更新") },
            text = { Text("当前版本 1.2（versionCode 3）。本项目由 Gitee 云流水线构建，请在仓库触发流水线获取新版本。") },
            confirmButton = { TextButton(onClick = { showUpdateDialog = false }) { Text("知道了") } }
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("将清空本机保存的全部 API Key（主题与长期记忆保留）。确定退出吗？") },
            confirmButton = {
                TextButton(onClick = {
                    store.clearAll()
                    showLogoutDialog = false
                    onLogout()
                }) { Text("退出", color = AppColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            }
        )
    }
}

// ==================== 主页面 ====================

@Composable
private fun MainPage(
    themeLabel: String,
    fontLabel: String,
    onAccount: () -> Unit,
    onData: () -> Unit,
    onLanguage: () -> Unit,
    onAppearance: () -> Unit,
    onFont: () -> Unit,
    onPersonalization: () -> Unit,
    onAbout: () -> Unit,
    onTts: () -> Unit,
    onVoiceSettings: () -> Unit,
    onUpdate: () -> Unit,
    onAgreement: () -> Unit,
    onHelp: () -> Unit
) {
    CardGroup("账户", listOf(
        RowItem(Icons.Default.Person, "账号管理", onClick = onAccount),
        RowItem(Icons.Default.Folder, "数据管理", onClick = onData)
    ))
    CardGroup("应用", listOf(
        RowItem(Icons.Default.Public, "语言", "中文 (简体中文)", onLanguage),
        RowItem(Icons.Default.Settings, "外观", themeLabel, onAppearance),
        RowItem(Icons.Default.TextFields, "字体大小", fontLabel, onFont),
        RowItem(Icons.Default.Edit, "个性化", onClick = onPersonalization)
    ))
    CardGroup("语音", listOf(
        RowItem(Icons.Default.VolumeUp, "朗读音色", onClick = onTts),
        RowItem(Icons.Default.Mic, "语音识别设置", "下载离线语音包", onVoiceSettings)
    ))
    CardGroup("关于", listOf(
        RowItem(Icons.Default.Info, "检查更新", "1.2 (3)", onUpdate),
        RowItem(Icons.Default.Description, "服务协议", onClick = onAgreement),
        RowItem(Icons.Default.Settings, "项目说明", onClick = onAbout)
    ))
    CardGroup("", listOf(
        RowItem(Icons.Default.HelpOutline, "帮助与反馈", onClick = onHelp)
    ))
}

// ==================== 子页面 ====================

@Composable
private fun AccountPage(
    apiKeys: List<ApiKeyEntry>,
    activeKeyId: String,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onMenu: (ApiKeyEntry) -> Unit,
    onOpenPlatform: () -> Unit,
    onLogout: () -> Unit
) {
    Text("已保存 ${apiKeys.size} 个 API Key，点击即可切换当前使用的密钥。",
        style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
    Column(
        modifier = Modifier.padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.Background)
    ) {
        apiKeys.forEachIndexed { index, entry ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onSwitch(entry.id) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Key, null, tint = AppColors.Primary,
                    modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.label, style = MaterialTheme.typography.bodyLarge)
                    Text(entry.masked, style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextSecondary)
                }
                if (entry.id == activeKeyId) {
                    Text("使用中", style = MaterialTheme.typography.labelSmall,
                        color = AppColors.Primary)
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = { onMenu(entry) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, null, tint = AppColors.TextSecondary)
                }
            }
            if (index < apiKeys.size - 1) Divider(color = AppColors.Divider)
        }
    }
    CardGroup("", listOf(
        RowItem(Icons.Default.Add, "添加 API Key", onClick = onAdd),
        RowItem(Icons.Default.Public, "前往开放平台创建", onClick = onOpenPlatform),
        RowItem(Icons.Default.Logout, "退出登录", onClick = onLogout)
    ))
}

@Composable
private fun DataPage(
    importing: Boolean,
    notice: String?,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    CardGroup("对话数据", listOf(
        RowItem(Icons.Default.FileDownload, "导入对话包", "网页版 deepseek_data-*.zip", onImport),
        RowItem(Icons.Default.FileUpload, "导出全部对话", "导出为 zip", onExport)
    ))
    if (importing) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("正在导入…", color = AppColors.TextSecondary)
        }
    }
    notice?.let {
        Text(it, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = AppColors.Success)
    }
}

@Composable
private fun AppearancePage(
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    defaultModel: String,
    onDefaultModelChange: (String) -> Unit
) {
    CardGroup("主题模式", ThemeMode.ALL.map { mode ->
        RowItem(
            icon = if (themeMode == mode) Icons.Default.Check else Icons.Default.Settings,
            title = ThemeMode.label(mode),
            value = ThemeMode.description(mode),
            onClick = { onThemeModeChange(mode) }
        )
    })
    CardGroup("默认模型", ModelOption.ALL.map { option ->
        RowItem(
            icon = Icons.Default.Check,
            title = option.label,
            value = if (defaultModel == option.id) "已选" else option.desc,
            onClick = { onDefaultModelChange(option.id) }
        )
    })
}

@Composable
private fun PersonalizationPage(
    memoryEnabled: Boolean,
    onMemoryEnabled: (Boolean) -> Unit,
    compressionEnabled: Boolean,
    onCompressionEnabled: (Boolean) -> Unit,
    cotVisible: Boolean,
    onCotVisible: (Boolean) -> Unit,
    memoryCount: Int,
    rebuilding: Boolean,
    onEditMemory: () -> Unit,
    onRebuild: () -> Unit,
    notice: String?
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.Background)
    ) {
        SwitchRow("长期记忆", "跨会话记住偏好与事实，最多 ${ContextEngine.MAX_FACTS} 条",
            memoryEnabled, onMemoryEnabled)
        Divider(color = AppColors.Divider)
        SwitchRow("上下文压缩", "历史过长时压缩较早轮次", compressionEnabled, onCompressionEnabled)
        Divider(color = AppColors.Divider)
        SwitchRow("显示思维链", "在回答上方展示推理过程与耗时", cotVisible, onCotVisible)
    }
    CardGroup("记忆管理", listOf(
        RowItem(Icons.Default.Psychology, "查看 / 编辑长期记忆", "已记住 $memoryCount 条", onEditMemory),
        RowItem(Icons.Default.Refresh,
            if (rebuilding) "正在重新提炼记忆…" else "从最近对话重新提炼",
            "扫描最近 8 个会话补漏", onRebuild)
    ))
    notice?.let {
        Text(it, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = AppColors.Success)
    }
}

@Composable
private fun AboutPage(context: Context) {
    CardGroup("赛博大鲸鱼", listOf(
        RowItem(Icons.Default.Info, "版本", "1.2 (3)", onClick = {}),
        RowItem(Icons.Default.Public, "API 服务", "api.deepseek.com", onClick = {
            openUrl(context, "https://api-docs.deepseek.com/zh-cn/")
        })
    ))
    Text(
        "非官方项目，仅供学习研究。使用 DeepSeek 官方开放 API，调用费用由你的账户承担；" +
            "API Key 加密保存在本机 Keystore，不会上传第三方。",
        style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
    )
}

// ==================== 通用组件 ====================

@Composable
private fun CardGroup(title: String, items: List<RowItem>) {
    if (title.isNotEmpty()) {
        Text(title, style = MaterialTheme.typography.labelMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))
    } else {
        Spacer(Modifier.height(16.dp))
    }
    Column(
        modifier = Modifier.padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.Background)
    ) {
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(onClick = item.onClick)
                    .padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(item.icon, null, tint = AppColors.TextPrimary,
                    modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text(item.title, style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                if (item.value.isNotEmpty()) {
                    Text(item.value, style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextSecondary, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                }
                Icon(Icons.Default.ChevronRight, null, tint = AppColors.TextSecondary,
                    modifier = Modifier.size(18.dp))
            }
            if (index < items.size - 1) Divider(color = AppColors.Divider)
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun fontLabel(scale: Float): String = when {
    scale >= 1.19f -> "超大"
    scale >= 1.09f -> "大"
    else -> "标准"
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
    }
}

private fun openTtsSettings(context: Context) {
    try {
        context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
    } catch (e: Exception) {
        Toast.makeText(context, "请在系统设置中调整文字转语音音色", Toast.LENGTH_SHORT).show()
    }
}

/** 语音能否离线用的关键：系统里是否装了中文离线语音包，给个直达入口 */
private fun openVoiceInputSettings(context: Context) {
    try {
        context.startActivity(Intent("android.settings.VOICE_INPUT_SETTINGS"))
    } catch (e: Exception) {
        Toast.makeText(
            context,
            "请在系统「设置 → 语言和输入法 → 语音输入」中下载中文离线语音包",
            Toast.LENGTH_LONG
        ).show()
    }
}
