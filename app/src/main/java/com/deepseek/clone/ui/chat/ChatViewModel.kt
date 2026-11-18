package com.deepseek.clone.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.clone.DeepSeekApp
import com.deepseek.clone.core.ApiConfig
import com.deepseek.clone.data.local.MessageEntity
import com.deepseek.clone.data.model.ContentPart
import com.deepseek.clone.data.model.ServerToolEvent
import com.deepseek.clone.data.model.UrlCitation
import com.deepseek.clone.data.remote.SseParser
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: com.deepseek.clone.data.repository.Repository =
        (app as DeepSeekApp).services.repository
    private val gson = Gson()

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _liveThinking = MutableStateFlow("")
    val liveThinking: StateFlow<String> = _liveThinking.asStateFlow()

    private val _liveContent = MutableStateFlow("")
    val liveContent: StateFlow<String> = _liveContent.asStateFlow()

    private val _toolEvents = MutableStateFlow<List<ServerToolEvent>>(emptyList())
    val toolEvents: StateFlow<List<ServerToolEvent>> = _toolEvents.asStateFlow()

    private val _liveCitations = MutableStateFlow<List<UrlCitation>>(emptyList())
    val liveCitations: StateFlow<List<UrlCitation>> = _liveCitations.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentModel = MutableStateFlow(ApiConfig.MODEL_FLASH)
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()

    /** 会话标题（自动命名 / 手动重命名后即时刷新顶栏） */
    private val _sessionTitle = MutableStateFlow("新对话")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    /** 待发送附件：改为可观察状态，上传完成后发送按钮会立即解禁 */
    private val _pendingParts = MutableStateFlow<List<ContentPart>>(emptyList())
    val pendingParts: StateFlow<List<ContentPart>> = _pendingParts.asStateFlow()

    private val _pendingNames = MutableStateFlow<List<String>>(emptyList())
    val pendingNames: StateFlow<List<String>> = _pendingNames.asStateFlow()

    /** CoT 是否展示 */
    private val _cotVisible = MutableStateFlow(true)
    val cotVisible: StateFlow<Boolean> = _cotVisible.asStateFlow()

    private var collectJob: Job? = null
    private var messagesJob: Job? = null
    private var currentSessionId: String? = null

    fun loadSession(sessionId: String) {
        refreshCotVisible()
        // 修复：collect 是无限流，原代码把读 session 放在 collect 之后，永远不会执行。
        // 拆成独立协程，模型与标题才能正确加载。
        if (currentSessionId != sessionId || messagesJob?.isActive != true) {
            currentSessionId = sessionId
            // 切换会话时先清空，保证 ChatScreen 的「初始定位到最新一轮」拿到的是本会话数据，
            // 避免沿用上一个会话的消息导致滚动错位。
            _messages.value = emptyList()
            messagesJob?.cancel()
            messagesJob = viewModelScope.launch {
                repo.observeMessages(sessionId).collect { _messages.value = it }
            }
        }
        // 每次进入会话都重读一次 session：从会话列表手动重命名后标题才能刷新
        viewModelScope.launch {
            val s = repo.getSession(sessionId)
            if (s != null) {
                // 导入会话等历史数据可能存有非法模型名（如 "default"），规范化并回写修复
                val model = ApiConfig.normalizeModel(s.modelType)
                if (model != s.modelType) repo.setSessionModel(sessionId, model)
                _currentModel.value = model
                _sessionTitle.value = s.title
            }
        }
    }

    /** 聊天页内重命名标题（会置 titleEdited，永久禁止自动标题覆盖） */
    fun renameTitle(title: String) {
        val id = currentSessionId ?: return
        val clean = title.trim().take(60)
        if (clean.isEmpty()) return
        _sessionTitle.value = clean
        viewModelScope.launch { repo.renameSession(id, clean) }
    }

    fun refreshCotVisible() {
        _cotVisible.value = (getApplication() as DeepSeekApp).services.store.cotVisible
    }

    fun setModel(model: String) {
        _currentModel.value = model
        currentSessionId?.let { id ->
            viewModelScope.launch { repo.setSessionModel(id, model) }
        }
    }

    /** 上传成功后添加附件（下一条消息携带）；name 仅用于本地 UI 展示 */
    fun addPendingPart(part: ContentPart, name: String) {
        _pendingParts.value = _pendingParts.value + part
        _pendingNames.value = _pendingNames.value + name
    }

    fun removePendingPart(index: Int) {
        _pendingParts.value = _pendingParts.value.filterIndexed { i, _ -> i != index }
        _pendingNames.value = _pendingNames.value.filterIndexed { i, _ -> i != index }
    }

    fun clearPendingParts() {
        _pendingParts.value = emptyList()
        _pendingNames.value = emptyList()
    }

    /** 手动重命名后同步顶栏，并阻止后续自动标题 */
    fun onTitleManuallyEdited(title: String) {
        _sessionTitle.value = title
    }

    /** 发送消息：把历史 + 新消息按 OpenAI messages 格式发给官方 API，流式接收。 */
    fun send(
        text: String,
        thinkingEnabled: Boolean,
        searchEnabled: Boolean,
        effort: String = ApiConfig.EFFORT_HIGH
    ) {
        val sessionId = currentSessionId ?: return
        val parts = _pendingParts.value.toList()
        if (text.isBlank() && parts.isEmpty()) return
        val names = _pendingNames.value.toList()
        sendCore(
            sessionId = sessionId,
            text = text,
            parts = parts,
            names = names,
            thinkingEnabled = thinkingEnabled,
            searchEnabled = searchEnabled,
            effort = effort,
            historyOverride = null,
            reuseUser = false,
            existingUserMsgId = 0
        )
    }

    /** 重新生成：删除最后一条助手回复，复用最后一条 user 消息重发（不重复落库 user） */
    fun regenerate(thinkingEnabled: Boolean, searchEnabled: Boolean) {
        val sessionId = currentSessionId ?: return
        if (_isGenerating.value) return
        val all = _messages.value
        val lastAssistant = all.lastOrNull { it.role == "assistant" } ?: return
        val lastUser = all.lastOrNull { it.role == "user" } ?: return
        // 历史 = 排除这最后一对消息
        val history = all.filter { it.uid != lastAssistant.uid && it.uid != lastUser.uid }
        val parts = repo.parseContentParts(lastUser.fileRefsJson).orEmpty()
        val names = repo.parseAttachmentNames(lastUser.attachmentNamesJson)
        viewModelScope.launch { repo.deleteMessage(lastAssistant.uid) }
        sendCore(
            sessionId = sessionId,
            text = lastUser.content,
            parts = parts,
            names = emptyList(),
            thinkingEnabled = thinkingEnabled,
            searchEnabled = searchEnabled,
            effort = ApiConfig.EFFORT_HIGH,
            historyOverride = history,
            reuseUser = true,
            existingUserMsgId = lastUser.messageId
        )
    }

    /**
     * 发送核心。
     * @param historyOverride 显式指定历史（重新生成时用）；null 表示用当前消息快照
     * @param reuseUser true 表示最后一条 user 消息已存在，不再落库，仅用它作为 parentId
     */
    private fun sendCore(
        sessionId: String,
        text: String,
        parts: List<ContentPart>,
        names: List<String>,
        thinkingEnabled: Boolean,
        searchEnabled: Boolean,
        effort: String,
        historyOverride: List<MessageEntity>?,
        reuseUser: Boolean,
        existingUserMsgId: Int
    ) {
        _error.value = null
        _isGenerating.value = true
        _liveThinking.value = ""
        _liveContent.value = ""
        _liveCitations.value = emptyList()
        _toolEvents.value = emptyList()

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis() / 1000.0
                val maxId = _messages.value.maxOfOrNull { it.messageId } ?: 0
                val userMsgId = if (reuseUser) existingUserMsgId else maxId + 1

                // 历史（不含本轮要生成的 assistant）
                val history = historyOverride ?: _messages.value.toList()

                // 1. 落库 user 消息（重新生成时复用已有，不重复落库）
                if (!reuseUser) {
                    repo.upsertMessageLocal(
                        MessageEntity(
                            sessionId = sessionId,
                            messageId = userMsgId,
                            role = "user",
                            content = text,
                            insertedAt = now,
                            modelType = _currentModel.value,
                            thinkingEnabled = thinkingEnabled,
                            searchEnabled = searchEnabled,
                            fileRefsJson = if (parts.isEmpty()) null else gson.toJson(parts),
                            attachmentNamesJson = if (names.isEmpty()) null else gson.toJson(names)
                        )
                    )
                    clearPendingParts()
                }

                // 2. 上下文工程：长期记忆 + 压缩摘要，得到 systemPrompt 与近期历史
                val (summary, recentHistory) = repo.context.prepareHistory(sessionId, history)
                val systemPrompt = repo.context.buildSystemPrompt(summary)

                // 3. 自动标题：仅首轮 + 从未手动重命名过时才请求服务端生成
                val session = repo.getSession(sessionId)
                val autoTitle = !reuseUser && history.isEmpty() && session?.titleEdited != true

                val messages = repo.buildMessages(systemPrompt, recentHistory, text, parts)
                val model = ApiConfig.normalizeModel(_currentModel.value)
                val request = repo.buildRequest(model, messages, thinkingEnabled, searchEnabled, effort, autoTitle)

                // 4. 流式请求
                val (response, flow) = repo.sendStream(request)
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code()}")
                }

                collectJob?.cancel()
                collectJob = launch {
                    var thinkingElapsed: Double? = null
                    val startAt = System.currentTimeMillis()
                    try {
                        flow.collect { ev ->
                            when (ev) {
                                is SseParser.Event.ThinkingDelta -> _liveThinking.value += ev.text
                                is SseParser.Event.ContentDelta -> {
                                    if (thinkingElapsed == null && _liveThinking.value.isNotEmpty()) {
                                        thinkingElapsed = (System.currentTimeMillis() - startAt) / 1000.0
                                    }
                                    _liveContent.value += ev.text
                                }
                                is SseParser.Event.ToolEvent -> {
                                    _toolEvents.value = _toolEvents.value + ev.event
                                }
                                is SseParser.Event.Citations -> _liveCitations.value = ev.list
                                is SseParser.Event.Title -> {
                                    if (autoTitle) {
                                        repo.applyAutoTitle(sessionId, ev.title)
                                        _sessionTitle.value = ev.title.trim().take(60)
                                    }
                                }
                                is SseParser.Event.Error -> _error.value = ev.message
                                is SseParser.Event.Finished -> Unit
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "stream collect", e)
                        _error.value = e.message ?: "流式接收失败"
                    } finally {
                        // 5. 落库助手消息
                        val answerId = (_messages.value.maxOfOrNull { it.messageId } ?: 1) + 1
                        val content = _liveContent.value
                        val thinking = _liveThinking.value
                        if (content.isNotEmpty() || thinking.isNotEmpty()) {
                            repo.upsertMessageLocal(
                                MessageEntity(
                                    sessionId = sessionId,
                                    messageId = answerId,
                                    parentId = userMsgId,
                                    role = "assistant",
                                    content = content,
                                    thinking = thinking.ifEmpty { null },
                                    thinkingElapsed = thinkingElapsed,
                                    searchResultsJson = if (_liveCitations.value.isEmpty()) null
                                    else gson.toJson(_liveCitations.value),
                                    modelType = model,
                                    thinkingEnabled = thinkingEnabled,
                                    searchEnabled = searchEnabled,
                                    status = if (_error.value != null) "FAILED" else "FINISHED",
                                    insertedAt = System.currentTimeMillis() / 1000.0 + 0.001,
                                    isError = _error.value != null
                                )
                            )
                        }
                        _isGenerating.value = false

                        // 6. 后台增量上下文压缩 + 长期记忆提取（失败静默，不阻断）
                        if (content.isNotEmpty()) {
                            launch {
                                runCatching {
                                    repo.context.compressInBackground(sessionId, history)
                                    repo.context.learn(text, content)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "send failed", e)
                _error.value = when {
                    e.message?.contains("401") == true -> "API Key 无效"
                    e.message?.contains("402") == true -> "账户余额不足"
                    else -> e.message ?: "发送失败"
                }
                _isGenerating.value = false
            }
        }
    }

    /** 官方 API 无 stop 端点，停止即取消本地收集（HTTP 连接随之关闭） */
    fun stop() {
        collectJob?.cancel()
        _isGenerating.value = false
    }

    fun parseCitations(json: String?): List<UrlCitation> = repo.parseCitations(json)

    fun parseAttachmentNames(json: String?): List<String> = repo.parseAttachmentNames(json)

    fun clearError() { _error.value = null }

    fun readAloud(text: String) {
        (getApplication() as? DeepSeekApp)?.readAloud?.speak(text)
    }

    override fun onCleared() {
        super.onCleared()
        messagesJob?.cancel()
        collectJob?.cancel()
    }
}
