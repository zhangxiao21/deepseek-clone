package com.deepseek.clone.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 轻量本地存储：用 SharedPreferences + Gson 保存会话与消息。
 *
 * 之所以不用 Room：本 App 数据量很小（纯文本会话），Room 的 kapt 注解处理在部分
 * 离线构建环境会因注解解析失败（NonExistentClass）导致编译中断。改用纯 Kotlin
 * 实现后不再依赖任何注解处理器，编译链更简单稳定。
 *
 * 对外 API（方法名/签名）与原 Room DAO 保持一致，上层代码无需改动。
 */

data class SessionEntity(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val modelType: String = "default",
    val source: String = "local"
)

data class MessageEntity(
    val uid: Long = 0,
    val sessionId: String,
    val messageId: Int = 0,
    val parentId: Int? = null,
    val role: String,
    val content: String,
    val thinking: String? = null,
    val thinkingElapsed: Double? = null,
    val searchResultsJson: String? = null,
    val modelType: String? = null,
    val thinkingEnabled: Boolean = false,
    val searchEnabled: Boolean = false,
    val status: String = "FINISHED",
    val insertedAt: Double = 0.0,
    val fileRefsJson: String? = null,
    val isError: Boolean = false
)

/** 线程安全的 JSON 存储。所有操作同步完成（数据量小），通过 Flow 通知 UI。 */
class ChatDao(context: Context) {
    private val gson = Gson()
    private val prefs: SharedPreferences =
        context.getSharedPreferences("deepseek_chat_db", Context.MODE_PRIVATE)

    private val sessionListType = object : TypeToken<MutableList<SessionEntity>>() {}.type
    private val messageListType = object : TypeToken<MutableList<MessageEntity>>() {}.type

    private var sessions: MutableList<SessionEntity> =
        load(KEY_SESSIONS, sessionListType) ?: mutableListOf()
    private var messages: MutableList<MessageEntity> =
        load(KEY_MESSAGES, messageListType) ?: mutableListOf()

    private val sessionsFlow = MutableStateFlow<List<SessionEntity>>(sessions.toList())
    private val messageFlows = mutableMapOf<String, MutableStateFlow<List<MessageEntity>>>()

    private val lock = Any()

    private fun <T> load(key: String, type: java.lang.reflect.Type): T? = try {
        val json = prefs.getString(key, null)
        if (json.isNullOrBlank()) null else gson.fromJson<T>(json, type)
    } catch (_: Exception) { null }

    private fun saveSessions() {
        prefs.edit().putString(KEY_SESSIONS, gson.toJson(sessions)).apply()
        sessionsFlow.value = sessions.sortedWith(
            compareByDescending<SessionEntity> { it.pinned }.thenByDescending { it.updatedAt }
        )
    }

    private fun saveMessages() {
        prefs.edit().putString(KEY_MESSAGES, gson.toJson(messages)).apply()
        messageFlows.keys.toList().forEach { sid ->
            messageFlows[sid]?.value = messages.filter { it.sessionId == sid }
                .sortedWith(compareBy<MessageEntity> { it.messageId }.thenBy { it.uid })
        }
    }

    // ---------- 会话 ----------

    fun observeSessions(): Flow<List<SessionEntity>> = sessionsFlow.asStateFlow()

    suspend fun getSessions(limit: Int): List<SessionEntity> = synchronized(lock) {
        sessions.sortedByDescending { it.updatedAt }.take(limit)
    }

    suspend fun getSession(id: String): SessionEntity? = synchronized(lock) {
        sessions.firstOrNull { it.id == id }
    }

    suspend fun upsertSession(session: SessionEntity) = synchronized(lock) {
        val idx = sessions.indexOfFirst { it.id == session.id }
        if (idx >= 0) sessions[idx] = session else sessions.add(session)
        saveSessions()
    }

    suspend fun updateTitle(id: String, title: String) = synchronized(lock) {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx >= 0) sessions[idx] = sessions[idx].copy(title = title)
        saveSessions()
    }

    suspend fun setPinned(id: String, pinned: Boolean) = synchronized(lock) {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx >= 0) sessions[idx] = sessions[idx].copy(pinned = pinned)
        saveSessions()
    }

    suspend fun deleteSession(id: String) = synchronized(lock) {
        sessions.removeAll { it.id == id }
        messages.removeAll { it.sessionId == id }
        saveSessions(); saveMessages()
    }

    // ---------- 消息 ----------

    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> = synchronized(lock) {
        messageFlows.getOrPut(sessionId) {
            MutableStateFlow(
                messages.filter { it.sessionId == sessionId }
                    .sortedWith(compareBy<MessageEntity> { it.messageId }.thenBy { it.uid })
            )
        }.asStateFlow()
    }

    suspend fun getMessages(sessionId: String): List<MessageEntity> = synchronized(lock) {
        messages.filter { it.sessionId == sessionId }
            .sortedWith(compareBy<MessageEntity> { it.messageId }.thenBy { it.uid })
    }

    suspend fun insertMessage(message: MessageEntity): Long = synchronized(lock) {
        val uid = if (message.uid == 0L) {
            (messages.maxOfOrNull { it.uid } ?: 0L) + 1L
        } else message.uid
        messages.add(message.copy(uid = uid))
        saveMessages()
        uid
    }

    suspend fun updateMessage(message: MessageEntity) = synchronized(lock) {
        val idx = messages.indexOfFirst { it.uid == message.uid }
        if (idx >= 0) messages[idx] = message
        saveMessages()
    }

    suspend fun deleteMessages(sessionId: String) = synchronized(lock) {
        messages.removeAll { it.sessionId == sessionId }
        saveMessages()
    }

    suspend fun maxMessageId(sessionId: String): Int? = synchronized(lock) {
        messages.filter { it.sessionId == sessionId }.maxOfOrNull { it.messageId }
    }

    suspend fun replaceMessages(sessionId: String, list: List<MessageEntity>) = synchronized(lock) {
        messages.removeAll { it.sessionId == sessionId }
        var uid = messages.maxOfOrNull { it.uid } ?: 0L
        list.forEach { m ->
            messages.add(m.copy(uid = ++uid, sessionId = sessionId))
        }
        saveMessages()
    }

    companion object {
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_MESSAGES = "messages"
    }
}
