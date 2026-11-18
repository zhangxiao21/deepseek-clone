package com.deepseek.clone.data.repository

import android.util.Log
import com.deepseek.clone.core.ApiConfig
import com.deepseek.clone.data.local.*
import com.deepseek.clone.data.model.*
import com.deepseek.clone.data.remote.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * 业务编排：基于 DeepSeek 官方开放 API。
 * 会话历史保存在本地 Room，每次请求把该会话的消息按 OpenAI messages 格式发给 API
 * （官方 API 无状态，不提供服务端会话）。
 */
class Repository(
    private val api: DeepSeekApi,
    private val dao: ChatDao,
    private val store: SecureStore
) {
    private val gson = Gson()

    // ---------- 密钥 ----------

    fun saveApiKey(key: String) {
        store.token = key.trim()
    }

    fun getApiKey(): String? = store.token

    fun hasApiKey(): Boolean = !store.token.isNullOrBlank()

    suspend fun validateApiKey(): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        try {
            val resp = api.listModels()
            if (resp.isSuccessful && resp.body() != null) {
                Result.success(resp.body()!!.data)
            } else {
                Result.failure(Exception("验证失败 HTTP ${resp.code()}: ${resp.errorBody()?.string()?.take(200)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun clearApiKey() {
        store.clearAll()
    }

    // ---------- 会话（纯本地） ----------

    fun observeSessions(): Flow<List<SessionEntity>> = dao.observeSessions()

    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> = dao.observeMessages(sessionId)

    suspend fun createLocalSession(title: String = "新对话", modelType: String = ApiConfig.MODEL_FLASH): String =
        withContext(Dispatchers.IO) {
            val id = "chat-" + java.util.UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            dao.upsertSession(SessionEntity(id, title, now, now, modelType = modelType))
            id
        }

    suspend fun renameSession(id: String, title: String) = withContext(Dispatchers.IO) {
        dao.updateTitle(id, title)
    }

    suspend fun setPinned(id: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        dao.setPinned(id, pinned)
    }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        dao.deleteSession(id)
    }

    suspend fun upsertMessageLocal(message: MessageEntity) = withContext(Dispatchers.IO) {
        dao.insertMessage(message)
    }

    suspend fun updateMessageLocal(message: MessageEntity) = withContext(Dispatchers.IO) {
        dao.updateMessage(message)
    }

    suspend fun getSession(id: String): SessionEntity? = withContext(Dispatchers.IO) { dao.getSession(id) }

    suspend fun getModelForSession(id: String): String = withContext(Dispatchers.IO) {
        dao.getSession(id)?.modelType ?: ApiConfig.MODEL_FLASH
    }

    suspend fun setSessionModel(id: String, model: String) = withContext(Dispatchers.IO) {
        dao.getSession(id)?.copy(modelType = model)?.let { dao.upsertSession(it) }
    }

    // ---------- 发送消息（官方 OpenAI 兼容） ----------

    /**
     * 构造 messages 数组并流式请求。
     * @param history 当前会话的历史消息（不含本次 user 消息）
     */
    fun buildMessages(
        systemPrompt: String,
        history: List<MessageEntity>,
        userText: String,
        userParts: List<ContentPart>? = null
    ): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        if (systemPrompt.isNotBlank()) {
            list.add(ChatMessage("system", systemPrompt))
        }
        for (m in history.takeLast(40)) {   // 控制上下文长度
            when (m.role) {
                "user" -> {
                    val parts = parseContentParts(m.fileRefsJson)
                    if (parts.isNullOrEmpty()) {
                        list.add(ChatMessage("user", m.content))
                    } else {
                        // 文本 + 图片/文件 part
                        val all = mutableListOf<ContentPart>()
                        if (m.content.isNotBlank()) all.add(ContentPart("text", text = m.content))
                        all.addAll(parts)
                        list.add(ChatMessage("user", all))
                    }
                }
                "assistant" -> {
                    // 官方 API 接受 reasoning_content 回传以维持思考上下文
                    list.add(ChatMessage("assistant", m.content))
                }
            }
        }
        if (!userParts.isNullOrEmpty()) {
            val all = mutableListOf<ContentPart>()
            if (userText.isNotBlank()) all.add(ContentPart("text", text = userText))
            all.addAll(userParts)
            list.add(ChatMessage("user", all))
        } else {
            list.add(ChatMessage("user", userText))
        }
        return list
    }

    /** 构造官方请求体 */
    fun buildRequest(
        model: String,
        messages: List<ChatMessage>,
        thinkingEnabled: Boolean,
        searchEnabled: Boolean,
        effort: String = ApiConfig.EFFORT_HIGH
    ): ChatRequest {
        val tools = if (searchEnabled) {
            listOf(
                BuiltinTool("deepseek_web_search", ApiConfig.TOOL_WEB_SEARCH),
                BuiltinTool("deepseek_read_url", ApiConfig.TOOL_READ_URL)
            )
        } else null
        return ChatRequest(
            model = model,
            messages = messages,
            stream = true,
            streamOptions = StreamOptions(includeUsage = true),
            thinking = Thinking(if (thinkingEnabled) ApiConfig.THINK_ENABLED else ApiConfig.THINK_DISABLED),
            reasoningEffort = if (thinkingEnabled) effort else null,
            tools = tools,
            title = TitleOption(true)
        )
    }

    /** 发起流式请求，返回 SSE Flow。调用方负责关闭 response body。 */
    suspend fun sendStream(request: ChatRequest): Pair<retrofit2.Response<okhttp3.ResponseBody>, Flow<SseParser.Event>> {
        val resp = api.chatCompletionsStream(request)
        val body = resp.body()
            ?: throw IllegalStateException("请求失败 HTTP ${resp.code()}: ${resp.errorBody()?.string()?.take(300)}")
        return resp to SseParser.parse(body)
    }

    // ---------- Files API ----------

    /** 上传文件/图片到官方 Files API，返回 file_id */
    suspend fun uploadFile(file: File, purpose: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val purposeBody = purpose.toRequestBody("text/plain".toMediaTypeOrNull())
            val fileBody = file.asRequestBody(guessMime(file))
            val part = MultipartBody.Part.createFormData("file", file.name, fileBody)
            val resp = api.uploadFile(purposeBody, part)
            val id = resp.body()?.id
            if (id != null) Result.success(id)
            else Result.failure(Exception("上传失败 HTTP ${resp.code()}: ${resp.errorBody()?.string()?.take(200)}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun guessMime(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "doc", "docx" -> "application/msword"
        "ppt", "pptx" -> "application/vnd.ms-powerpoint"
        "xls", "xlsx" -> "application/vnd.ms-excel"
        "txt", "md", "csv", "json" -> "text/plain"
        else -> "application/octet-stream"
    }

    /** 判断文件用途：图片走 user_data，文档走 rich_layout_recognition */
    fun purposeFor(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "txt", "md", "csv", "json" -> ApiConfig.PURPOSE_USER_DATA
        else -> ApiConfig.PURPOSE_RICH_LAYOUT
    }

    // ---------- 工具 ----------

    fun parseContentParts(json: String?): List<ContentPart>? {
        if (json.isNullOrBlank()) return null
        return try {
            gson.fromJson<List<ContentPart>>(json, object : TypeToken<List<ContentPart>>() {}.type)
        } catch (_: Exception) { null }
    }

    fun parseCitations(json: String?): List<UrlCitation> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<UrlCitation>>() {}.type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    suspend fun clearMessages(sessionId: String) = withContext(Dispatchers.IO) {
        dao.deleteMessages(sessionId)
    }
}
