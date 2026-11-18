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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import com.deepseek.clone.core.ApiConfig
import com.deepseek.clone.core.ModelOption
import com.deepseek.clone.data.local.MessageEntity
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

    var input by remember { mutableStateOf("") }
    var thinkingEnabled by remember { mutableStateOf(true) }
    var searchEnabled by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var pendingCount by remember { mutableStateOf(viewModel.getPendingParts().size) }
    val listState = rememberLazyListState()

    LaunchedEffect(sessionId) { viewModel.loadSession(sessionId) }
    LaunchedEffect(messages.size, liveContent) {
        if (messages.isNotEmpty() || isGenerating) {
            runCatching {
                listState.animateScrollToItem(maxOf(0, if (isGenerating) messages.size else messages.size - 1))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(sessionTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(ModelOption.find(model).label,
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.TextSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).background(Color.White)) {
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (messages.isEmpty() && !isGenerating) item { EmptyChatHint() }

                items(messages, key = { it.uid }) { msg ->
                    MessageBubble(
                        msg = msg,
                        parseCitations = { viewModel.parseCitations(it) },
                        onCopy = { copyToClipboard(context, msg.content) },
                        onReadAloud = { viewModel.readAloud(msg.content) }
                    )
                }

                if (isGenerating) {
                    item {
                        LiveResponse(
                            thinking = liveThinking,
                            content = liveContent,
                            toolEvents = toolEvents,
                            citations = citations
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
                pendingCount = pendingCount,
                onSend = {
                    viewModel.send(input, thinkingEnabled, searchEnabled)
                    input = ""
                    pendingCount = 0
                },
                onStop = { viewModel.stop() },
                onAttach = onAttachFile,
                onImage = onPickImage,
                onVoice = { onVoiceInput { text -> input = if (input.isBlank()) text else "$input $text" } }
            )
        }
    }
}

@Composable
private fun EmptyChatHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🐋", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(8.dp))
        Text("给 DeepSeek 发送消息", style = MaterialTheme.typography.titleMedium,
            color = AppColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        Text("开启深度思考或联网搜索以获得更强能力", style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextSecondary)
    }
}

@Composable
private fun MessageBubble(
    msg: MessageEntity,
    parseCitations: (String?) -> List<UrlCitation>,
    onCopy: () -> Unit,
    onReadAloud: () -> Unit
) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) { Avatar(isUser = false); Spacer(Modifier.width(8.dp)) }
        Column(
            modifier = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(if (isUser) 18.dp else 6.dp))
                    .background(if (isUser) AppColors.BubbleUser else Color.Transparent)
                    .padding(if (isUser) 12.dp else 0.dp)
            ) {
                if (isUser) {
                    Text(msg.content, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                } else {
                    MarkdownText(msg.content.ifEmpty { if (msg.isError) "生成失败" else "" })
                }
            }
            if (!msg.thinking.isNullOrEmpty()) ThinkingBlock(msg.thinking, msg.thinkingElapsed)
            val cites = parseCitations(msg.searchResultsJson)
            if (cites.isNotEmpty()) CitationsCard(cites)
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
                }
            }
        }
        if (isUser) { Spacer(Modifier.width(8.dp)); Avatar(isUser = true) }
    }
}

@Composable
private fun Avatar(isUser: Boolean) {
    Box(
        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
            .background(if (isUser) AppColors.SurfaceVariant else AppColors.Primary),
        contentAlignment = Alignment.Center
    ) {
        Text(if (isUser) "我" else "D",
            color = if (isUser) AppColors.TextSecondary else Color.White,
            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ThinkingBlock(thinking: String, elapsed: Double?) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(AppColors.Surface).padding(10.dp)
    ) {
        Row(modifier = Modifier.clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically) {
            Text("💭 深度思考", style = MaterialTheme.typography.labelMedium, color = AppColors.Primary)
            Spacer(Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = "展开", tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
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
    citations: List<UrlCitation>
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Avatar(isUser = false)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.widthIn(max = 300.dp)) {
            val searching = toolEvents.any { it.kind in listOf("search", "deep_search", "image_search") }
            if (searching && content.isEmpty()) {
                Text("🔍 正在联网搜索…", style = MaterialTheme.typography.labelMedium,
                    color = AppColors.Primary)
            }
            if (citations.isNotEmpty()) CitationsCard(citations)
            if (thinking.isNotEmpty()) ThinkingBlock(thinking, null)
            if (content.isNotEmpty()) {
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
    pendingCount: Int,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onImage: () -> Unit,
    onVoice: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), shadowElevation = 8.dp, color = Color.White) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
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
            if (pendingCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text("📎 已添加 $pendingCount 个附件", style = MaterialTheme.typography.labelSmall,
                    color = AppColors.Primary)
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
                    colors = OutlinedTextFieldDefaults.colors(
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
                        enabled = input.isNotBlank() || pendingCount > 0,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = AppColors.Primary)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
                }
            }
            Row {
                IconButton(onClick = onImage, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Image, contentDescription = "图片",
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
    clipboard.setPrimaryClip(ClipData.newPlainText("DeepSeek", text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}
