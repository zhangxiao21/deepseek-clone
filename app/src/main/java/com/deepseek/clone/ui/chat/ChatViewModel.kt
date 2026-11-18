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
import com.google.gson.reflect.TypeToken
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

    private val _liveTitle = MutableStateFlow<String?>(null)
    val liveTitle: StateFlow<String?> = _liveTitle.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentModel = MutableStateFlow(ApiConfig.MODEL_FLASH)
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()

    private var collectJob: Job? = null
    private var currentSessionId: String? = null
    private var pendingParts: MutableList<ContentPart> = mutableListOf()

    fun loadSession(sessionId: String) {
        currentSessionId = sessionId
        viewModelScope.launch {
            repo.observeMessages(sessionId).collect { _messages.value = it }
            _currentModel.value = repo.getModelForSession(sessionId)
        }
    }

    fun setModel(model: String) {
        _currentModel.value = model
        currentSessionId?.let { id ->
            viewModelScope.launch { repo.setSessionModel(id, model) }
        }
    }

    /** 上传成功后添加图片/文件 part（下一条消息携带） */
    fun addPendingPart(part: ContentPart) {
        pendingParts.add(part)
    }

    fun clearPendingParts() {
        pendingParts.clear()
    }

    fun getPendingParts(): List<ContentPart> = pendingParts.toList()

    /**
     * 发送消息：把历史 + 新消息按 OpenAI messages 格式发给官方 API，流式接收。
     */
    fun send(
        text: String,
        thinkingEnabled: Boolean,
        searchEnabled: Boolean,
        effort: String = ApiConfig.EFFORT_HIGH
    ) {
        val sessionId = currentSessionId ?: return
        if (text.isBlank() && pendingParts.isEmpty()) return
        _error.value = null
        _isGenerating.value = true
        _liveThinking.value = ""
        _liveContent.value = ""
        _liveCitations.value = emptyList()
        _toolEvents.value = emptyList()
        _liveTitle.value = null

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis() / 1000.0
                val maxId = _messages.value.maxOfOrNull { it.messageId } ?: 0
                val userMsgId = maxId + 1

                // 1. 落库 user 消息（含附件 part）
                val parts = pendingParts.toList()
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
                        fileRefsJson = if (parts.isEmpty()) null else gson.toJson(parts)
                    )
                )
                pendingParts.clear()

                // 2. 构造 messages（历史 + 当前 user）
                // 过滤掉刚落库的当前 user 消息，避免重复发送
                val history = _messages.value.toList().filter { it.messageId != userMsgId }
                val messages = repo.buildMessages(
                    systemPrompt = "You are a helpful assistant.",
                    history = history,
                    userText = text,
                    userParts = parts
                )
                val model = _currentModel.value
                val request = repo.buildRequest(model, messages, thinkingEnabled, searchEnabled, effort)

                // 3. 流式请求
                val (response, flow) = repo.sendStream(request)
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code()}")
                }

                collectJob?.cancel()
                collectJob = launch {
                    var result: SseParser.StreamResult? = null
                    try {
                        flow.collect { ev ->
                            when (ev) {
                                is SseParser.Event.ThinkingDelta -> _liveThinking.value += ev.text
                                is SseParser.Event.ContentDelta -> _liveContent.value += ev.text
                                is SseParser.Event.ToolEvent -> {
                                    _toolEvents.value = _toolEvents.value + ev.event
                                }
                                is SseParser.Event.Citations -> _liveCitations.value = ev.list
                                is SseParser.Event.Title -> {
                                    _liveTitle.value = ev.title
                                    viewModelScope.launch { repo.renameSession(sessionId, ev.title) }
                                }
                                is SseParser.Event.Error -> _error.value = ev.message
                                is SseParser.Event.Finished -> result = ev.result
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "stream collect", e)
                        _error.value = e.message ?: "流式接收失败"
                    } finally {
                        // 4. 落库助手消息
                        val r = result
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

    fun clearError() { _error.value = null }

    fun readAloud(text: String) {
        (getApplication() as? DeepSeekApp)?.readAloud?.speak(text)
    }
}
