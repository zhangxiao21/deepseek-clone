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
 * 会话历史保存在本地，每次请求把该会话的消息按 OpenAI messages 格式发给 API
 * （官方 API 无状态，不提供服务端会话）。
 */
class Repository(
    private val api: DeepSeekApi,
    private val dao: ChatDao,
    private val store: SecureStore
) {
    private val gson = Gson()

    /** 长期记忆 + 上下文压缩引擎 */
    val context = ContextEngine(api, store, dao)

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

    /**
     * 手动重新提炼长期记忆：扫描最近若干会话的历史消息，分批抽取后合并。
     * 用于补齐早期版本（单轮 300 token / 40 条上限）漏记的信息。
     */
    suspend fun rebuildMemoryFromHistory(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val sessions = dao.getSessions(ContextEngine.REBUILD_SESSIONS)
            val all = ArrayList<MessageEntity>()
            sessions.forEach { s ->
                all.addAll(dao.getMessages(s.id).takeLast(ContextEngine.REBUILD_MESSAGES_PER_SESSION))
            }
            Result.success(context.rebuildFromHistory(all).size)
        } catch (e: Exception) {
            Result.failure(e)
        }
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

    /** 用户在会话列表手动重命名：置 titleEdited，此后不再被自动标题覆盖 */
    suspend fun renameSession(id: String, title: String) = withContext(Dispatchers.IO) {
        dao.updateTitle(id, title, manual = true)
    }

    /** 服务端自动生成的标题：仅当会话从未被手动重命名过时生效 */
    suspend fun applyAutoTitle(id: String, title: String) = withContext(Dispatchers.IO) {
        val clean = title.trim().take(60)
        if (clean.isEmpty()) return@withContext
        val s = dao.getSession(id) ?: return@withContext
        if (s.titleEdited) return@withContext
        dao.updateTitle(id, clean, manual = false)
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
        // 兜底校验：历史脏数据（如导入会话的 "default"）不能原样发给服务端
        ApiConfig.normalizeModel(dao.getSession(id)?.modelType)
    }

    suspend fun setSessionModel(id: String, model: String) = withContext(Dispatchers.IO) {
        dao.getSession(id)?.copy(modelType = model)?.let { dao.upsertSession(it) }
    }

    // ---------- 发送消息（官方 OpenAI 兼容） ----------

    /**
     * 把 content part 规范化，避免官方 API 报
     * "file cannot have filename when file_id is present"。
     *
     * 规则：引用已上传文件（file_id）时，content part 只能有 type + file_id，
     * filename / file_data / text / image_url 全部不能带。历史消息里若存过旧的
     * 带 filename 的 part（旧版本 APK 落库），这里也一并清洗，保证向下兼容。
     */
    fun sanitizeParts(parts: List<ContentPart>): List<ContentPart> = parts.mapNotNull { p ->
        when {
            !p.fileId.isNullOrBlank() -> ContentPart(type = "file", fileId = p.fileId)
            p.type == "text" && !p.text.isNullOrBlank() -> ContentPart(type = "text", text = p.text)
            p.type == "image_url" && p.imageUrl != null -> ContentPart(type = "image_url", imageUrl = p.imageUrl)
            p.type == "file" && !p.fileData.isNullOrBlank() ->
                ContentPart(type = "file", fileData = p.fileData, filename = p.filename ?: "attachment")
            else -> null
        }
    }

    /**
     * 构造 messages 数组。
     * @param history 已经是「压缩后摘要 + 需要回传的近期历史」处理过的结果
     * @param contextSummary 较早轮次的压缩摘要，注入 system（与长期记忆一起由 ContextEngine 处理）
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
        for (m in history) {
            when (m.role) {
                "user" -> {
                    val parts = parseContentParts(m.fileRefsJson)?.let { sanitizeParts(it) }
                    if (parts.isNullOrEmpty()) {
                        list.add(ChatMessage("user", m.content))
                    } else {
                        val all = mutableListOf<ContentPart>()
                        if (m.content.isNotBlank()) all.add(ContentPart("text", text = m.content))
                        all.addAll(parts)
                        list.add(ChatMessage("user", all))
                    }
                }
                "assistant" -> {
                    // 正文回传即可；thinking(CoT) 属于推理过程，不回传给 API
                    list.add(ChatMessage("assistant", m.content))
                }
            }
        }
        val cleanParts = userParts?.let { sanitizeParts(it) }
        if (!cleanParts.isNullOrEmpty()) {
            val all = mutableListOf<ContentPart>()
            if (userText.isNotBlank()) all.add(ContentPart("text", text = userText))
            all.addAll(cleanParts)
            list.add(ChatMessage("user", all))
        } else {
            list.add(ChatMessage("user", userText))
        }
        return list
    }

    /**
     * 构造官方请求体。
     * @param autoTitle 仅当会话未被手动重命名、且是首轮时才为 true；
     *                  否则不向服务端请求标题，服务端就不会每轮返回标题事件覆盖用户的命名。
     */
    fun buildRequest(
        model: String,
        messages: List<ChatMessage>,
        thinkingEnabled: Boolean,
        searchEnabled: Boolean,
        effort: String = ApiConfig.EFFORT_HIGH,
        autoTitle: Boolean = false
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
            title = if (autoTitle) TitleOption(true) else null
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
            val fileBody = file.asRequestBody(guessMime(file).toMediaTypeOrNull())
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

    /** 附件名列表（仅本地 UI 展示，绝不进入 API 请求） */
    fun parseAttachmentNames(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    suspend fun deleteMessage(uid: Long) = withContext(Dispatchers.IO) {
        dao.deleteMessage(uid)
    }

    suspend fun clearMessages(sessionId: String) = withContext(Dispatchers.IO) {
        dao.deleteMessages(sessionId)
    }
}
