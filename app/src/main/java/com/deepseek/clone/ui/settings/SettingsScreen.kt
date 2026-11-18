package com.deepseek.clone.ui.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.deepseek.clone.R
import com.deepseek.clone.core.ApiConfig
import com.deepseek.clone.data.importer.ChatZipManager
import com.deepseek.clone.data.local.SecureStore
import com.deepseek.clone.ui.theme.AppColors
import com.deepseek.clone.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    store: SecureStore,
    zipManager: ChatZipManager,
    repository: com.deepseek.clone.data.repository.Repository,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLogoutDialog by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<String?>(null) }

    // 记忆 / 上下文 / CoT 设置
    var memoryEnabled by remember { mutableStateOf(store.memoryEnabled) }
    var compressionEnabled by remember { mutableStateOf(store.compressionEnabled) }
    var cotVisible by remember { mutableStateOf(store.cotVisible) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    var memoryFacts by remember { mutableStateOf(repository.context.memoryFacts()) }
    var memoryNotice by remember { mutableStateOf<String?>(null) }
    var rebuildingMemory by remember { mutableStateOf(false) }

    // 多 API Key
    var apiKeys by remember { mutableStateOf(store.apiKeys()) }
    var activeKeyId by remember { mutableStateOf(store.activeApiKey()?.id.orEmpty()) }
    var showAddKey by remember { mutableStateOf(false) }
    var keyMenu by remember { mutableStateOf<com.deepseek.clone.data.local.ApiKeyEntry?>(null) }
    var editingKey by remember { mutableStateOf<com.deepseek.clone.data.local.ApiKeyEntry?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            importing = true
            scope.launch {
                val result = zipManager.importFromUri(it)
                importing = false
                importResult = result.fold(
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
                Toast.makeText(context,
                    result.fold({ "已导出 ${it} 个对话" }, { "导出失败：${it.message}" }),
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = stringResource(R.string.model_settings)) {
                Text("默认模型", style = MaterialTheme.typography.labelMedium,
                    color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                com.deepseek.clone.core.ModelOption.ALL.forEach { option ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { store.defaultModelType = option.id }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = store.defaultModelType == option.id,
                            onClick = { store.defaultModelType = option.id })
                        Column {
                            Text(option.label)
                            Text(option.desc, style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextSecondary)
                        }
                    }
                }
            }

            Divider(color = AppColors.Divider)

            SettingsSection(title = stringResource(R.string.appearance)) {
                ThemeMode.ALL.forEach { mode ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onThemeModeChange(mode) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(ThemeMode.label(mode))
                            Text(ThemeMode.description(mode),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextSecondary)
                        }
                    }
                }
            }

            Divider(color = AppColors.Divider)

            // ---------- 记忆与上下文 ----------
            SettingsSection(title = "记忆与上下文") {
                SettingToggle(
                    title = "长期记忆",
                    subtitle = "跨会话记住你的偏好与事实，自动注入每次对话",
                    checked = memoryEnabled,
                    onCheckedChange = { v ->
                        memoryEnabled = v
                        store.memoryEnabled = v
                    }
                )
                SettingToggle(
                    title = "上下文压缩",
                    subtitle = "历史过长时把较早轮次压成摘要，保留最近对话原文，控制 token",
                    checked = compressionEnabled,
                    onCheckedChange = { v ->
                        compressionEnabled = v
                        store.compressionEnabled = v
                    }
                )
                SettingToggle(
                    title = "显示思维链 (CoT)",
                    subtitle = "在回答上方展示完整推理过程与耗时",
                    checked = cotVisible,
                    onCheckedChange = { v ->
                        cotVisible = v
                        store.cotVisible = v
                    }
                )
                SettingItem(
                    icon = Icons.Default.Psychology,
                    title = "查看 / 编辑长期记忆",
                    subtitle = "当前已记住 ${memoryFacts.size} 条（上限 ${com.deepseek.clone.data.repository.ContextEngine.MAX_FACTS} 条）",
                    onClick = {
                        memoryFacts = repository.context.memoryFacts()
                        showMemoryDialog = true
                    }
                )
                SettingItem(
                    icon = Icons.Default.Refresh,
                    title = if (rebuildingMemory) "正在重新提炼记忆…" else "从最近对话重新提炼记忆",
                    subtitle = "扫描最近 8 个会话，补充之前漏记的偏好与事实",
                    onClick = {
                        if (!rebuildingMemory) {
                            rebuildingMemory = true
                            scope.launch {
                                val result = repository.rebuildMemoryFromHistory()
                                rebuildingMemory = false
                                memoryFacts = repository.context.memoryFacts()
                                memoryNotice = result.fold(
                                    { "提炼完成，当前共 $it 条长期记忆" },
                                    { "提炼失败：${it.message}" }
                                )
                            }
                        }
                    }
                )
                memoryNotice?.let {
                    Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = AppColors.Success)
                }
            }

            Divider(color = AppColors.Divider)

            // ---------- 多 API Key ----------
            SettingsSection(title = "API Key（可保存多个并随时切换）") {
                apiKeys.forEach { entry ->
                    val active = entry.id == activeKeyId
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                store.setActiveApiKey(entry.id)
                                activeKeyId = entry.id
                                memoryNotice = null
                                Toast.makeText(context, "已切换到「${entry.label}」", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = active, onClick = {
                            store.setActiveApiKey(entry.id)
                            activeKeyId = entry.id
                        })
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.label, style = MaterialTheme.typography.bodyLarge)
                            Text(entry.masked, style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextSecondary)
                        }
                        if (active) {
                            Text("使用中", style = MaterialTheme.typography.labelSmall,
                                color = AppColors.Primary)
                            Spacer(Modifier.width(8.dp))
                        }
                        IconButton(onClick = { keyMenu = entry }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreVert, null, tint = AppColors.TextSecondary)
                        }
                    }
                }
                TextButton(
                    onClick = { showAddKey = true },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加 API Key")
                }
            }

            Divider(color = AppColors.Divider)

            SettingsSection(title = stringResource(R.string.import_chat)) {
                SettingItem(
                    icon = Icons.Default.FileDownload,
                    title = stringResource(R.string.import_chat),
                    subtitle = stringResource(R.string.import_chat_desc),
                    onClick = { importLauncher.launch("application/zip") }
                )
                SettingItem(
                    icon = Icons.Default.FileUpload,
                    title = stringResource(R.string.export_chat),
                    subtitle = "导出所有本地对话为 zip",
                    onClick = { exportLauncher.launch("deepseek_chats_${System.currentTimeMillis()}.zip") }
                )
                if (importing) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在导入…", color = AppColors.TextSecondary)
                    }
                }
                importResult?.let {
                    Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("成功")) AppColors.Success else AppColors.Error)
                }
            }

            Divider(color = AppColors.Divider)

            SettingsSection(title = "API 接口") {
                Text(
                    "赛博大鲸鱼使用 DeepSeek 官方开放 API（api.deepseek.com），调用费用从你的 API 账户余额扣除。",
                    style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                SettingItem(
                    icon = Icons.Default.Key,
                    title = "管理 API Key",
                    subtitle = "前往 platform.deepseek.com/api_keys",
                    onClick = {
                        context.startActivity(android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(ApiConfig.API_KEY_URL)
                        ))
                    }
                )
            }

            Divider(color = AppColors.Divider)

            SettingsSection(title = stringResource(R.string.account)) {
                SettingItem(
                    icon = Icons.Default.Logout,
                    title = stringResource(R.string.logout),
                    onClick = { showLogoutDialog = true },
                    tint = AppColors.Error
                )
            }

            Divider(color = AppColors.Divider)

            SettingsSection(title = stringResource(R.string.about)) {
                Text(stringResource(R.string.about_text),
                    style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                    modifier = Modifier.padding(16.dp))
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // ---------- 添加 / 编辑 API Key ----------
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
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("备注名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = { keyText = it; keyError = null },
                        label = { Text("API Key (sk-...)") },
                        singleLine = true,
                        isError = keyError != null,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                TextButton(onClick = { showAddKey = false; editingKey = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    keyMenu?.let { entry ->
        AlertDialog(
            onDismissRequest = { keyMenu = null },
            title = { Text(entry.label) },
            text = { Text(entry.masked, color = AppColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    editingKey = entry
                    keyMenu = null
                }) { Text("编辑") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        store.removeApiKey(entry.id)
                        apiKeys = store.apiKeys()
                        activeKeyId = store.activeApiKey()?.id.orEmpty()
                        keyMenu = null
                    }) { Text("删除", color = AppColors.Error) }
                    TextButton(onClick = { keyMenu = null }) { Text(stringResource(R.string.cancel)) }
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
                    Text("每行一条。对话结束后会自动补充新的事实；也可以手动编辑或清空。",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                        maxLines = 10
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val list = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    repository.context.setMemoryFacts(list)
                    memoryFacts = repository.context.memoryFacts()
                    showMemoryDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        repository.context.clearMemory()
                        memoryFacts = emptyList()
                        memoryNotice = "已清空长期记忆"
                        showMemoryDialog = false
                    }) { Text("清空", color = AppColors.Error) }
                    TextButton(onClick = { showMemoryDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.logout)) },
            text = { Text(stringResource(R.string.logout_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    store.clearAll()
                    onLogout()
                }) { Text(stringResource(R.string.ok), color = AppColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = AppColors.Primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
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

@Composable
private fun SettingItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = AppColors.TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
            }
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppColors.TextSecondary)
    }
}
