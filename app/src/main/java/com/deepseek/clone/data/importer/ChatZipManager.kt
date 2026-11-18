package com.deepseek.clone.data.importer

import android.content.Context
import android.net.Uri
import com.deepseek.clone.data.local.ChatDao
import com.deepseek.clone.data.local.MessageEntity
import com.deepseek.clone.data.local.SessionEntity
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 导入/导出 DeepSeek 网页版对话包。
 *
 * 导出包结构（export_format_version: 3）：
 *   manifest.json             {"export_format_version": 3}
 *   chat_session_index.json   [{id, updated_at, ...}]
 *   chat_sessions/{id}.json   {title, current_message_id, messages:[...]}
 *
 * 消息是 parent_id 树；按 current_message_id 回溯摊平；过滤 CONTENT_FILTER/失败态。
 */
class ChatZipManager(private val context: Context, private val dao: ChatDao) {

    private val gson = Gson()

    data class ImportResult(val sessions: Int, val messages: Int)

    suspend fun importFromUri(uri: Uri): Result<ImportResult> = withContext(Dispatchers.IO) {
        var zip: ZipFile? = null
        try {
            // 复制到私有临时文件（Uri 可能是 content://）
            val tmp = File.createTempFile("ds_import", ".zip", context.cacheDir)
            context.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            zip = ZipFile(tmp)
            importZip(zip)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { zip?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun importZip(zip: ZipFile): Result<ImportResult> {
        val entries = zip.entries().toList().map { it.name }
        if (!entries.any { it.endsWith("manifest.json") || it == "manifest.json" }) {
            return Result.failure(Exception("不是有效的 DeepSeek 导出包（缺少 manifest.json）"))
        }

        // 找 chat_sessions/*.json
        val sessionEntries = entries.filter {
            (it.startsWith("chat_sessions/") || it.contains("/chat_sessions/")) && it.endsWith(".json")
        }
        if (sessionEntries.isEmpty()) {
            return Result.failure(Exception("导出包中没有找到对话文件"))
        }

        var totalSessions = 0
        var totalMessages = 0

        for (name in sessionEntries) {
            val sessionId = File(name).nameWithoutExtension
            val json = zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readText() }
            val obj = gson.fromJson(json, JsonObject::class.java)
            val title = obj.get("title")?.asString?.takeIf { it.isNotBlank() } ?: "导入的对话"
            val currentId = obj.get("current_message_id")?.asInt
            val msgsArray = obj.getAsJsonArray("messages") ?: continue

            val messages = mutableListOf<ImportedMessage>()
            for (el in msgsArray) {
                if (!el.isJsonObject) continue
                val m = el.asJsonObject
                try {
                    messages.add(ImportedMessage(
                        id = m.get("id")?.asInt ?: continue,
                        parentId = m.get("parent_id")?.let { if (it.isJsonNull) null else it.asInt },
                        role = m.get("role")?.asString ?: continue,
                        status = m.get("status")?.asString,
                        insertedAt = m.get("inserted_at")?.asDouble ?: 0.0,
                        fragments = parseFragments(m.getAsJsonArray("fragments"))
                    ))
                } catch (_: Exception) {}
            }

            val flat = flattenTree(messages, currentId)
            val now = System.currentTimeMillis()
            val localId = "imported-$sessionId"
            dao.upsertSession(
                SessionEntity(
                    id = localId,
                    title = title,
                    createdAt = now,
                    updatedAt = now,
                    modelType = "default",
                    source = "imported"
                )
            )
            var mid = 0
            val entities = mutableListOf<MessageEntity>()
            for (m in flat) {
                // 过滤被内容过滤整轮（user + 紧邻 assistant CONTENT_FILTER）
                if (m.status == "CONTENT_FILTER" || m.fragments.any { it.type == "CONTENT_FILTER" }) continue
                val isError = m.status != null && m.status != "FINISHED"
                val content = m.fragments.filter { it.type == "RESPONSE" || it.type == "REQUEST" }
                    .mapNotNull { it.content }.joinToString("")
                val thinking = m.fragments.filter { it.type == "THINK" }
                    .mapNotNull { it.content }.joinToString("").ifEmpty { null }
                if (content.isEmpty() && thinking.isNullOrEmpty() && !isError) continue
                mid++
                entities.add(
                    MessageEntity(
                        sessionId = localId,
                        messageId = mid,
                        parentId = if (mid > 1) mid - 1 else null,
                        role = m.role,
                        content = content,
                        thinking = thinking,
                        status = m.status ?: "FINISHED",
                        insertedAt = m.insertedAt,
                        isError = isError
                    )
                )
            }
            dao.replaceMessages(localId, entities)
            totalSessions++
            totalMessages += entities.size
        }
        return Result.success(ImportResult(totalSessions, totalMessages))
    }

    private data class ImportedMessage(
        val id: Int,
        val parentId: Int?,
        val role: String,
        val status: String?,
        val insertedAt: Double,
        val fragments: List<Fragment>
    )

    private data class Fragment(val type: String, val content: String?)

    private fun parseFragments(arr: com.google.gson.JsonArray?): List<Fragment> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            Fragment(
                type = o.get("type")?.asString ?: return@mapNotNull null,
                content = o.get("content")?.asString
            )
        }.filter { it.type !in IGNORED_FRAGMENT_TYPES }
    }

    /** 沿 current_message_id 回溯到根，摊平为当前分支 */
    private fun flattenTree(messages: List<ImportedMessage>, currentId: Int?): List<ImportedMessage> {
        if (messages.isEmpty()) return emptyList()
        val byId = messages.associateBy { it.id }
        val branch = mutableSetOf<Int>()
        var cursor: Int? = currentId ?: messages.maxOf { it.id }
        while (cursor != null && byId.containsKey(cursor) && branch.add(cursor)) {
            cursor = byId[cursor]?.parentId
        }
        return messages.filter { it.id in branch }.sortedBy { it.id }
    }

    /** 导出所有本地会话为 zip */
    suspend fun exportAll(targetUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val sessions = dao.getSessions(Int.MAX_VALUE)
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                ZipOutputStream(out).use { zip ->
                    // manifest
                    putEntry(zip, "manifest.json", "{\"export_format_version\":3}")
                    // index
                    val index = sessions.map { mapOf("id" to it.id, "updated_at" to it.updatedAt / 1000.0) }
                    putEntry(zip, "chat_session_index.json", gson.toJson(index))
                    // sessions
                    for (s in sessions) {
                        val msgs = dao.getMessages(s.id)
                        val obj = JsonObject().apply {
                            addProperty("title", s.title)
                            if (msgs.isNotEmpty()) addProperty("current_message_id", msgs.last().messageId)
                            val arr = com.google.gson.JsonArray()
                            msgs.forEach { m ->
                                val mo = JsonObject().apply {
                                    addProperty("id", m.messageId)
                                    if (m.parentId != null) addProperty("parent_id", m.parentId)
                                    addProperty("role", m.role)
                                    addProperty("status", m.status)
                                    addProperty("inserted_at", m.insertedAt)
                                    val frags = com.google.gson.JsonArray()
                                    if (!m.thinking.isNullOrEmpty()) {
                                        frags.add(JsonObject().apply {
                                            addProperty("type", "THINK"); addProperty("content", m.thinking)
                                        })
                                    }
                                    frags.add(JsonObject().apply {
                                        addProperty("type", "RESPONSE"); addProperty("content", m.content)
                                    })
                                    add("fragments", frags)
                                }
                                arr.add(mo)
                            }
                            add("messages", arr)
                        }
                        putEntry(zip, "chat_sessions/${s.id}.json", gson.toJson(obj))
                    }
                }
            } ?: return@withContext Result.failure(Exception("无法写入目标文件"))
            Result.success(sessions.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun putEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(java.util.zip.ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    companion object {
        private val IGNORED_FRAGMENT_TYPES = setOf(
            "TOOL_FIND", "TOOL_IMAGE_SEARCH", "PARTIAL_FILE_READ", "TIP",
            "subagent/descriptor", "request/header", "request/context",
            "tool/ptc-dispatch", "compaction"
        )
    }
}
