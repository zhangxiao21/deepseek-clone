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
                    subtitle = "当前已记住 ${memoryFacts.size} 条",
                    onClick = {
                        memoryFacts = repository.context.memoryFacts()
                        showMemoryDialog = true
                    }
                )
                memoryNotice?.let {
                    Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = AppColors.Success)
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
