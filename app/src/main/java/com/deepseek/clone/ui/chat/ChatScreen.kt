package com.deepseek.clone.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepseek.clone.R
import com.deepseek.clone.core.ModelOption
import com.deepseek.clone.data.local.MessageEntity
import com.deepseek.clone.data.model.ContentPart
import com.deepseek.clone.data.model.ServerToolEvent
import com.deepseek.clone.data.model.UrlCitation
import com.deepseek.clone.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    sessionTitle: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onAttachFile: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onVoiceInput: (callback: (String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val liveThinking by viewModel.liveThinking.collectAsState()
    val liveContent by viewModel.liveContent.collectAsState()
    val toolEvents by viewModel.toolEvents.collectAsState()
    val citations by viewModel.liveCitations.collectAsState()
    val error by viewModel.error.collectAsState()
    val model by viewModel.currentModel.collectAsState()
    val vmTitle by viewModel.sessionTitle.collectAsState()
    val cotVisible by viewModel.cotVisible.collectAsState()
    val pendingParts by viewModel.pendingParts.collectAsState()
    val pendingNames by viewModel.pendingNames.collectAsState()

    var input by remember { mutableStateOf("") }
    var thinkingEnabled by remember { mutableStateOf(true) }
    var searchEnabled by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val title = vmTitle.ifBlank { sessionTitle }

    LaunchedEffect(sessionId) { viewModel.loadSession(sessionId) }

    // 打开会话：直接（无动画）定位到「最新一轮对话」，避免从顶部一路往下滚很久。
    var initialScrolled by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId, messages.size) {
        if (!initialScrolled && messages.isNotEmpty()) {
            runCatching { listState.scrollToItem(maxOf(0, messages.size - 1)) }
            initialScrolled = true
        }
    }

    // 生成过程中：仅当用户本来就靠近底部时才跟随，不打断向上翻阅历史。
    // 直接读 layoutInfo 而非 derivedStateOf（后者在 Compose 1.2.1 需额外 opt-in）。
    LaunchedEffect(liveContent, liveThinking, isGenerating) {
        if (isGenerating) {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= info.totalItemsCount - 2) {
                runCatching { listState.scrollToItem(maxOf(0, messages.size)) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { showRename = true }) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(ModelOption.find(model).label,
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.TextSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showModelMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "模型")
                        }
                        DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                            ModelOption.ALL.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(option.label)
                                            Text(option.desc, style = MaterialTheme.typography.labelSmall,
                                                color = AppColors.TextSecondary)
                                        }
                                    },
                                    onClick = {
                                        viewModel.setModel(option.id)
                                        showModelMenu = false
                                    },
                                    leadingIcon = {
                                        if (model == option.id) Icon(Icons.Default.Check, null,
                                            tint = AppColors.Primary)
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(containerColor = AppColors.Background)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).background(AppColors.Background)) {
            error?.let { msg ->
                Snackbar(
                    modifier = Modifier.padding(8.dp),
                    action = { TextButton(onClick = { viewModel.clearError() }) { Text("知道了") } }
                ) { Text(msg) }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (messages.isEmpty() && !isGenerating) item { EmptyChatHint() }

                items(messages, key = { it.uid }) { msg ->
                    val isLastAssistant = msg.role == "assistant" &&
                        messages.lastOrNull { it.role == "assistant" }?.uid == msg.uid
                    MessageBubble(
                        msg = msg,
                        cotVisible = cotVisible,
                        parseCitations = { viewModel.parseCitations(it) },
                        parseAttachmentNames = { viewModel.parseAttachmentNames(it) },
                        onCopy = { copyToClipboard(context, msg.content) },
                        onReadAloud = { viewModel.readAloud(msg.content) },
                        onRegenerate = if (isLastAssistant && !isGenerating) {
                            { viewModel.regenerate(thinkingEnabled, searchEnabled) }
                        } else null
                    )
                }

                if (isGenerating) {
                    item {
                        LiveResponse(
                            thinking = liveThinking,
                            content = liveContent,
                            toolEvents = toolEvents,
                            citations = citations,
                            cotVisible = cotVisible
                        )
                    }
                }
            }

            InputBar(
                input = input,
                onInputChange = { input = it },
                thinkingEnabled = thinkingEnabled,
                onThinkingToggle = { thinkingEnabled = it },
                searchEnabled = searchEnabled,
                onSearchToggle = { searchEnabled = it },
                isGenerating = isGenerating,
                pendingParts = pendingParts,
                pendingNames = pendingNames,
                onRemovePending = { viewModel.removePendingPart(it) },
                onSend = {
                    viewModel.send(input, thinkingEnabled, searchEnabled)
                    input = ""
                },
                onStop = { viewModel.stop() },
                onAttach = onAttachFile,
                onImage = onPickImage,
                onCamera = onTakePhoto,
                onVoice = { onVoiceInput { text -> input = if (input.isBlank()) text else "$input $text" } }
            )
        }
    }

    if (showRename) {
        var text by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameTitle(text)
                    showRename = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun EmptyChatHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 90.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🐋", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text("我是赛博大鲸鱼，很高兴见到你", style = MaterialTheme.typography.titleMedium,
            color = AppColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text("可以问我任何问题，或开启深度思考 / 联网搜索", style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary)
    }
}

@Composable
private fun MessageBubble(
    msg: MessageEntity,
    cotVisible: Boolean,
    parseCitations: (String?) -> List<UrlCitation>,
    parseAttachmentNames: (String?) -> List<String>,
    onCopy: () -> Unit,
    onReadAloud: () -> Unit,
    onRegenerate: (() -> Unit)?
) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) { Avatar(isUser = false); Spacer(Modifier.width(8.dp)) }
        Column(
            // AI 消息占满剩余宽度（官方风格：无气泡、全宽排版）；用户消息限制最大宽度
            modifier = if (isUser) Modifier.widthIn(max = 300.dp) else Modifier.weight(1f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // 思维链 CoT：官方是先思考后回答，思维块置于正文上方
            if (!isUser && cotVisible && !msg.thinking.isNullOrEmpty()) {
                ThinkingBlock(msg.thinking, msg.thinkingElapsed, live = false)
                Spacer(Modifier.height(8.dp))
            }
            Box(
                modifier = Modifier
                    .clip(
                        if (isUser) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                        else RoundedCornerShape(6.dp)
                    )
                    .background(if (isUser) AppColors.BubbleUser else Color.Transparent)
                    .padding(if (isUser) 12.dp else 0.dp)
            ) {
                if (isUser) {
                    Column {
                        Text(msg.content, color = AppColors.OnBubbleUser,
                            style = MaterialTheme.typography.bodyLarge)
                        val names = parseAttachmentNames(msg.attachmentNamesJson)
                        if (names.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            names.forEach { n ->
                                Text("📎 $n", color = AppColors.OnBubbleUser,
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                } else {
                    MarkdownText(msg.content.ifEmpty { if (msg.isError) "生成失败" else "" })
                }
            }
            val cites = parseCitations(msg.searchResultsJson)
            if (!isUser && cites.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                CitationsCard(cites)
            }
            if (!isUser && msg.content.isNotEmpty()) {
                Row {
                    IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制",
                            tint = AppColors.TextSecondary, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onReadAloud, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.VolumeUp, contentDescription = "朗读",
                            tint = AppColors.TextSecondary, modifier = Modifier.size(16.dp))
                    }
                    if (onRegenerate != null) {
                        IconButton(onClick = onRegenerate, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "重新生成",
                                tint = AppColors.TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        if (isUser) { Spacer(Modifier.width(8.dp)); Avatar(isUser = true) }
    }
}

@Composable
private fun Avatar(isUser: Boolean) {
    Box(
        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(10.dp))
            .background(if (isUser) AppColors.SurfaceVariant else AppColors.Primary),
        contentAlignment = Alignment.Center
    ) {
        Text(if (isUser) "我" else "🐋",
            color = if (isUser) AppColors.TextSecondary else AppColors.OnPrimary,
            fontWeight = if (isUser) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium)
    }
}

/** 思维链（CoT）展示块：显示耗时、可展开查看完整推理过程，生成中默认展开 */
@Composable
private fun ThinkingBlock(thinking: String, elapsed: Double?, live: Boolean) {
    var expanded by remember { mutableStateOf(live) }
    val headline = when {
        elapsed != null -> "已深度思考（用时 %.1f 秒）".format(elapsed)
        live -> "正在深度思考…"
        else -> "已深度思考"
    }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(AppColors.Surface).padding(10.dp)
    ) {
        Row(modifier = Modifier.clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Psychology, contentDescription = null,
                tint = AppColors.Primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(headline, style = MaterialTheme.typography.labelMedium, color = AppColors.Primary)
            Spacer(Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = "展开思维链", tint = AppColors.TextSecondary,
                modifier = Modifier.size(18.dp))
        }
        // 折叠态给出首行预览，避免完全看不到 CoT
        if (!expanded) {
            val preview = thinking.lineSequence().firstOrNull().orEmpty().take(48)
            if (preview.isNotEmpty()) {
                Text(preview + if (thinking.length > 48) "…" else "",
                    style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                    maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
        }
        AnimatedVisibility(visible = expanded) {
            Text(thinking, style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun CitationsCard(cites: List<UrlCitation>) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(AppColors.Surface).padding(10.dp)
    ) {
        Row(modifier = Modifier.clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically) {
            Text("🔍 引用 ${cites.size} 个来源", style = MaterialTheme.typography.labelMedium,
                color = AppColors.Primary)
            Spacer(Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = "展开", tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                cites.take(10).forEach { c ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(c.title ?: c.url ?: "", style = MaterialTheme.typography.bodySmall,
                            color = AppColors.Primary, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveResponse(
    thinking: String,
    content: String,
    toolEvents: List<ServerToolEvent>,
    citations: List<UrlCitation>,
    cotVisible: Boolean
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Avatar(isUser = false)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            val searching = toolEvents.any { it.kind in listOf("search", "deep_search", "image_search") }
            if (searching && content.isEmpty()) {
                Text("🔍 正在联网搜索…", style = MaterialTheme.typography.labelMedium,
                    color = AppColors.Primary)
            }
            if (citations.isNotEmpty()) CitationsCard(citations)
            if (cotVisible && thinking.isNotEmpty()) {
                ThinkingBlock(thinking, null, live = true)
            }
            if (content.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                MarkdownText(content)
            } else if (thinking.isEmpty() && !searching) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                        color = AppColors.Primary)
                    Spacer(Modifier.width(8.dp))
                    Text("思考中…", style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextSecondary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    input: String,
    onInputChange: (String) -> Unit,
    thinkingEnabled: Boolean,
    onThinkingToggle: (Boolean) -> Unit,
    searchEnabled: Boolean,
    onSearchToggle: (Boolean) -> Unit,
    isGenerating: Boolean,
    pendingParts: List<ContentPart>,
    pendingNames: List<String>,
    onRemovePending: (Int) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onImage: () -> Unit,
    onCamera: () -> Unit,
    onVoice: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), shadowElevation = 8.dp, color = AppColors.Background) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // 待发送附件（可单独移除）；上传完成即出现，发送按钮随之解禁
            if (pendingParts.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📎 ${pendingParts.size} 个附件", style = MaterialTheme.typography.labelSmall,
                        color = AppColors.Primary)
                }
                pendingNames.forEachIndexed { index, name ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) {
                        Text(name, style = MaterialTheme.typography.labelSmall,
                            color = AppColors.TextSecondary, maxLines = 1,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = { onRemovePending(index) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "移除附件",
                                tint = AppColors.TextSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = thinkingEnabled,
                    onClick = { onThinkingToggle(!thinkingEnabled) },
                    label = { Text("深度思考") },
                    leadingIcon = { Icon(Icons.Default.Psychology, null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppColors.PrimaryLight,
                        selectedLabelColor = AppColors.Primary)
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = searchEnabled,
                    onClick = { onSearchToggle(!searchEnabled) },
                    label = { Text("联网搜索") },
                    leadingIcon = { Icon(Icons.Default.Public, null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppColors.PrimaryLight,
                        selectedLabelColor = AppColors.Primary)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttach, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.AttachFile, contentDescription = "文件", tint = AppColors.TextSecondary)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 120.dp),
                    placeholder = { Text(stringResource(R.string.chat_hint)) },
                    shape = RoundedCornerShape(22.dp),
                    maxLines = 4,
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = AppColors.Primary,
                        unfocusedBorderColor = AppColors.Divider)
                )
                Spacer(Modifier.width(4.dp))
                if (isGenerating) {
                    FilledIconButton(onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = AppColors.Primary)) {
                        Icon(Icons.Default.Stop, contentDescription = "停止")
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = input.isNotBlank() || pendingParts.isNotEmpty(),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = AppColors.Primary)
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "发送")
                    }
                }
            }
            Row {
                IconButton(onClick = onImage, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Image, contentDescription = "图片",
                        tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onCamera, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "拍照",
                        tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onVoice, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Mic, contentDescription = "语音",
                        tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("赛博大鲸鱼", text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}
