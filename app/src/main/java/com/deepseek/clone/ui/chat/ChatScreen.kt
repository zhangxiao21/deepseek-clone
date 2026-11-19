package com.deepseek.clone.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.deepseek.clone.R
import com.deepseek.clone.core.ModelOption
import com.deepseek.clone.data.local.MessageEntity
import com.deepseek.clone.data.model.ContentPart
import com.deepseek.clone.data.model.ServerToolEvent
import com.deepseek.clone.data.model.UrlCitation
import com.deepseek.clone.ui.picker.RecentImagesStrip
import com.deepseek.clone.ui.theme.AppColors
import com.deepseek.clone.ui.theme.WhaleLogo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    sessionTitle: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onNewChat: () -> Unit,
    onAttachFile: () -> Unit,
    onPickImages: () -> Unit,
    onQuickImages: (List<Uri>) -> Unit,
    onTakePhoto: () -> Unit,
    onVoiceInput: (callback: (String) -> Unit) -> Unit,
    onVoiceStop: () -> Unit,
    voiceListening: Boolean
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
    val pendingPaths by viewModel.pendingPaths.collectAsState()

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
                    // 官方顶栏：中央为鲸鱼图标 + DeepSeek 字标；进入对话后点击可切换模型 / 重命名
                    Box {
                        Column(
                            modifier = Modifier
                                .clickable { if (messages.isNotEmpty()) showModelMenu = true },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (messages.isEmpty()) {
                                BrandWordmark()
                            } else {
                                Text(title, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, fontWeight = FontWeight.SemiBold)
                                Text(ModelOption.find(model).label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.TextSecondary)
                            }
                        }
                        DropdownMenu(expanded = showModelMenu,
                            onDismissRequest = { showModelMenu = false }) {
                            ModelOption.ALL.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(option.label)
                                            Text(option.desc,
                                                style = MaterialTheme.typography.labelSmall,
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
                            DropdownMenuItem(
                                text = { Text("重命名对话") },
                                onClick = { showModelMenu = false; showRename = true }
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Menu, contentDescription = "会话列表",
                            tint = AppColors.TextSecondary)
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Default.Add, contentDescription = "新对话",
                            tint = AppColors.TextSecondary)
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
                        parseAttachmentPaths = { viewModel.parseAttachmentPaths(it) },
                        onCopy = { copyToClipboard(context, msg.content) },
                        onReadAloud = { viewModel.readAloud(msg.content) },
                        onStopReadAloud = { viewModel.stopReadAloud() },
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
                            cotVisible = cotVisible,
                            modelLabel = ModelOption.find(model).label
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
                pendingPaths = pendingPaths,
                onRemovePending = { viewModel.removePendingPart(it) },
                onSend = {
                    viewModel.send(input, thinkingEnabled, searchEnabled)
                    input = ""
                },
                onStop = { viewModel.stop() },
                onAttach = onAttachFile,
                onImages = onPickImages,
                onQuickImages = onQuickImages,
                onCamera = onTakePhoto,
                onVoice = { onVoiceInput { text -> input = if (input.isBlank()) text else "$input $text" } },
                onVoiceStop = onVoiceStop,
                voiceListening = voiceListening
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
        modifier = Modifier.fillMaxWidth().padding(top = 110.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WhaleLogo(modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(22.dp))
        Text("你好，我能帮什么忙吗？",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary)
    }
}

/** 原版品牌字标：鲸鱼图标 + "DeepSeek"，其中 "Seek" 染品牌蓝 */
@Composable
private fun BrandWordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WhaleLogo(modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            buildAnnotatedString {
                append("Deep")
                withStyle(SpanStyle(color = AppColors.Primary, fontWeight = FontWeight.Bold)) {
                    append("Seek")
                }
            },
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/**
 * 扁平化鲸鱼标志见 ui/theme/WhaleLogo.kt（共享组件 WhaleLogo）。
 */

@Composable
private fun MessageBubble(
    msg: MessageEntity,
    cotVisible: Boolean,
    parseCitations: (String?) -> List<UrlCitation>,
    parseAttachmentNames: (String?) -> List<String>,
    parseAttachmentPaths: (String?) -> List<String>,
    onCopy: () -> Unit,
    onReadAloud: () -> Unit,
    onStopReadAloud: () -> Unit,
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
            // 官方：AI 回复顶部带模型名（鲸鱼图标 + 模型名），用户消息无头部
            if (!isUser) {
                Text(
                    ModelOption.find(msg.modelType ?: "").label,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
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
                    .border(
                        if (isUser) BorderStroke(1.dp, AppColors.BubbleUserBorder) else BorderStroke(0.dp, Color.Transparent),
                        if (isUser) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(6.dp)
                    )
                    .padding(if (isUser) 12.dp else 0.dp)
            ) {
                if (isUser) {
                    Column {
                        Text(msg.content, color = AppColors.OnBubbleUser,
                            style = MaterialTheme.typography.bodyLarge)
                        val names = parseAttachmentNames(msg.attachmentNamesJson)
                        val paths = parseAttachmentPaths(msg.attachmentUrisJson)
                        val count = maxOf(names.size, paths.size)
                        if (count > 0) {
                            Spacer(Modifier.height(8.dp))
                            repeat(count) { i ->
                                val name = names.getOrNull(i) ?: "附件 ${i + 1}"
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                ) {
                                    AttachmentThumb(paths.getOrNull(i).orEmpty(), name,
                                        Modifier.size(42.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(name, color = AppColors.OnBubbleUser,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, modifier = Modifier.weight(1f))
                                }
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
                            tint = AppColors.TextTertiary, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onReadAloud, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.VolumeUp, contentDescription = "朗读",
                            tint = AppColors.TextTertiary, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onStopReadAloud, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Stop, contentDescription = "停止朗读",
                            tint = AppColors.TextTertiary, modifier = Modifier.size(16.dp))
                    }
                    if (onRegenerate != null) {
                        IconButton(onClick = onRegenerate, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "重新生成",
                                tint = AppColors.TextTertiary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        if (isUser) { Spacer(Modifier.width(8.dp)) }
    }
}

@Composable
private fun Avatar(isUser: Boolean) {
    if (isUser) {
        // 原版用户消息无头像，仅气泡右对齐；这里不渲染占位
        return
    }
    WhaleLogo(modifier = Modifier.size(28.dp))
}

/** 思维链（CoT）展示块：原版凹陷面板 + 左侧 2pt 蓝色竖线 + 斜体灰色思考文字，可折叠 */
@Composable
private fun ThinkingBlock(thinking: String, elapsed: Double?, live: Boolean) {
    var expanded by remember { mutableStateOf(live) }
    val headline = when {
        elapsed != null -> "已深度思考（用时 %.1f 秒）".format(elapsed)
        live -> "正在深度思考…"
        else -> "已深度思考"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.ReasoningBg)
    ) {
        // 左侧 2pt 品牌蓝竖线贯穿整个面板高度
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(AppColors.Primary)
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Psychology, contentDescription = null,
                    tint = AppColors.Primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(headline, style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextSecondary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "展开思维链", tint = AppColors.TextTertiary,
                    modifier = Modifier.size(18.dp))
            }
            // 折叠态给出首行预览，避免完全看不到 CoT
            if (!expanded) {
                val preview = thinking.lineSequence().firstOrNull().orEmpty().take(48)
                if (preview.isNotEmpty()) {
                    Text(preview + if (thinking.length > 48) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.ReasoningText,
                        fontStyle = FontStyle.Italic,
                        maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                }
            }
            AnimatedVisibility(visible = expanded) {
                // 原版：思考文字是斜体、比正文浅一档，是「内心独白」信号
                Text(thinking, style = MaterialTheme.typography.bodySmall,
                    color = AppColors.ReasoningText,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 8.dp))
            }
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
    cotVisible: Boolean,
    modelLabel: String
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Avatar(isUser = false)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(modelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp))
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
    pendingPaths: List<String>,
    onRemovePending: (Int) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onImages: () -> Unit,
    onQuickImages: (List<Uri>) -> Unit,
    onCamera: () -> Unit,
    onVoice: () -> Unit,
    onVoiceStop: () -> Unit,
    voiceListening: Boolean
) {
    var showAttachPanel by remember { mutableStateOf(false) }
    val canSend = input.isNotBlank() || pendingParts.isNotEmpty()

    Surface(modifier = Modifier.fillMaxWidth(), color = AppColors.Background) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // 待发送附件：每张图都有缩略图 + 文件名，能一眼看清传了哪几张
            if (pendingNames.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pendingNames.forEachIndexed { index, name ->
                        Column(
                            modifier = Modifier
                                .width(96.dp)
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppColors.SurfaceVariant)
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box {
                                AttachmentThumb(
                                    path = pendingPaths.getOrNull(index).orEmpty(),
                                    name = name,
                                    modifier = Modifier.size(66.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(18.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(AppColors.Error.copy(alpha = 0.9f))
                                        .clickable { onRemovePending(index) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "移除",
                                        tint = Color.White, modifier = Modifier.size(12.dp))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(name, style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextPrimary, maxLines = 1)
                        }
                    }
                }
            }

            // 官方风格：一整块圆角胶囊，输入区在上、功能 chips 在下
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(AppColors.Surface)
                    .border(BorderStroke(1.dp, AppColors.Divider), RoundedCornerShape(26.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // 官方默认态：输入区居中「按住说话」，点击进入文字输入、长按直接唤起语音
                var textMode by remember { mutableStateOf(false) }
                if (!textMode && input.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { textMode = true },
                                    onLongPress = { onVoice() }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (voiceListening) "正在聆听… 点击麦克风结束" else "按住说话",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (voiceListening) AppColors.Primary else AppColors.TextPrimary
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 110.dp),
                        placeholder = {
                            Text("发消息…", color = AppColors.TextSecondary,
                                style = MaterialTheme.typography.bodyMedium)
                        },
                        maxLines = 4,
                        shape = RoundedCornerShape(18.dp),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillChip(
                        selected = thinkingEnabled,
                        icon = Icons.Default.Psychology,
                        label = "深度思考",
                        onClick = { onThinkingToggle(!thinkingEnabled) }
                    )
                    Spacer(Modifier.width(8.dp))
                    PillChip(
                        selected = searchEnabled,
                        icon = Icons.Default.Public,
                        label = "智能搜索",
                        onClick = { onSearchToggle(!searchEnabled) }
                    )
                    Spacer(Modifier.weight(1f))

                    if (isGenerating) {
                        CircleButton(Icons.Default.Stop, "停止生成", onStop, filled = true,
                            fillColor = AppColors.Error)
                        Spacer(Modifier.width(8.dp))
                    } else if (canSend) {
                        CircleButton(Icons.Default.ArrowUpward, "发送", onSend, filled = true)
                        Spacer(Modifier.width(8.dp))
                    }

                    CircleButton(
                        icon = if (showAttachPanel) Icons.Default.Close else Icons.Default.Add,
                        description = if (showAttachPanel) "收起附件面板" else "添加图片或文件",
                        onClick = { showAttachPanel = !showAttachPanel }
                    )
                    Spacer(Modifier.width(8.dp))
                    CircleButton(
                        icon = if (voiceListening) Icons.Default.Close else Icons.Default.Mic,
                        description = if (voiceListening) "结束语音输入" else "语音输入",
                        onClick = { if (voiceListening) onVoiceStop() else onVoice() },
                        filled = voiceListening
                    )
                }
            }

            // 「+」展开：官方风格的最近图片横条 + 拍照 / 相册 / 文件 三个大按钮
            if (showAttachPanel) {
                RecentImagesStrip(onPick = { uri -> onQuickImages(listOf(uri)) })
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AttachTile(Icons.Default.PhotoCamera, "拍照",
                        modifier = Modifier.weight(1f)) {
                        showAttachPanel = false; onCamera()
                    }
                    AttachTile(Icons.Default.Image, "相册",
                        modifier = Modifier.weight(1f)) {
                        showAttachPanel = false; onImages()
                    }
                    AttachTile(Icons.Default.AttachFile, "文件",
                        modifier = Modifier.weight(1f)) {
                        showAttachPanel = false; onAttach()
                    }
                }
            }

            if (voiceListening) {
                Text(
                    "正在聆听… 点击麦克风结束",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.Primary,
                    modifier = Modifier.padding(start = 14.dp, top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun PillChip(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val bg = if (selected) AppColors.PrimaryLight else Color.Transparent
    val fg = if (selected) AppColors.Primary else AppColors.TextPrimary
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(BorderStroke(1.dp, if (selected) AppColors.Primary else AppColors.Divider), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = fg, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun CircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    filled: Boolean = false,
    fillColor: Color = AppColors.Primary
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (filled) fillColor else Color.Transparent)
            .border(
                BorderStroke(1.dp, if (filled) Color.Transparent else AppColors.Divider),
                androidx.compose.foundation.shape.CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description,
            tint = if (filled) AppColors.OnPrimary else AppColors.TextPrimary,
            modifier = Modifier.size(19.dp))
    }
}

/** 「+」面板里的大方块按钮：拍照 / 相册 / 文件 */
@Composable
private fun AttachTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .height(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.SurfaceVariant)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, label, tint = AppColors.TextPrimary, modifier = Modifier.size(25.dp))
        Spacer(Modifier.height(7.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextPrimary, fontWeight = FontWeight.Medium)
    }
}

/** 附件缩略图：本地缓存文件还在就显示真实图片，否则退化为通用文件图标 */
@Composable
private fun AttachmentThumb(path: String, name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exists = remember(path) {
        path.isNotBlank() && try {
            java.io.File(path).exists()
        } catch (_: Exception) { false }
    }
    val shape = RoundedCornerShape(8.dp)
    if (exists) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(java.io.File(path))
                .size(240)
                .crossfade(true)
                .build(),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape).background(AppColors.SurfaceVariant)
        )
    } else {
        Box(
            modifier = modifier.clip(shape).background(AppColors.SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.AttachFile, contentDescription = name,
                tint = AppColors.TextSecondary, modifier = Modifier.size(22.dp))
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("赛博大鲸鱼", text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}
