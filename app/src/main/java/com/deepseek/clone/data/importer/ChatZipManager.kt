package com.deepseek.clone.data.importer

import android.content.Context
import android.net.Uri
import com.deepseek.clone.data.local.ChatDao
import com.deepseek.clone.data.local.MessageEntity
import com.deepseek.clone.data.local.SessionEntity
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 导入/导出 DeepSeek 网页版对话包（manifest.json: export_format_version = 3）。
 *
 * 真实导出结构（已用 deepseek_data-*.zip 实测）：
 *   chat_sessions/{uuid}.json {
 *     "id", "title", "pinned", "current_message_id", "inserted_at", "updated_at",
 *     "messages": [
 *       { "id": 1, "parent_id": null, "role": "USER" | "ASSISTANT",
 *         "status": "FINISHED", "thinking_enabled": true, "search_enabled": false,
 *         "inserted_at": "2026-11-15T16:13:25.628Z",   // ISO 字符串，不是数字！
 *         "fragments": [ {"type":"REQUEST|RESPONSE|THINK|FILE|...", "content": ...} ] }
 *     ]
 *   }
 *
 * 三个必须处理的坑（否则「标题有、内容没有」）：
 *   1. inserted_at 是 ISO 字符串，旧代码用 asDouble 解析抛异常，异常被 catch 静默吞掉 → 全部消息丢失。
 *   2. role 是大写 USER / ASSISTANT，需归一化为 user / assistant，否则气泡判错、历史不进上下文。
 *   3. content 可能是 JSON null / 缺失 / 数字，必须用安全访问器，不能对 JsonNull 调 asString。
 *
 * 消息是 parent_id 树，按 current_message_id 回溯摊平为网页版当前显示的分支。
 */
class ChatZipManager(private val context: Context, private val dao: ChatDao) {

    private val gson = Gson()

    data class ImportResult(
        val sessions: Int,
        val messages: Int,
        /** 无法解析而跳过的消息数，用于让导入结果可见、可排查 */
        val skipped: Int = 0
    )

    suspend fun importFromUri(uri: Uri): Result<ImportResult> = withContext(Dispatchers.IO) {
        var zip: ZipFile? = null
        try {
            // 复制到私有临时文件（Uri 可能是 content://）
            val tmp = File.createTempFile("ds_import", ".zip", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext Result.failure(Exception("无法读取所选文件"))
            val opened = ZipFile(tmp)
            zip = opened
            importZip(opened)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { zip?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun importZip(zip: ZipFile): Result<ImportResult> {
        val entries = zip.entries().toList().map { it.name }
        if (entries.none { it == "manifest.json" || it.endsWith("/manifest.json") }) {
            return Result.failure(Exception("不是有效的 DeepSeek 导出包（缺少 manifest.json）"))
        }

        // 找 chat_sessions/*.json，排除附件目录 *_files/
        val sessionEntries = entries.filter {
            it.endsWith(".json") &&
                (it.startsWith("chat_sessions/") || it.contains("/chat_sessions/")) &&
                !it.contains("_files/")
        }
        if (sessionEntries.isEmpty()) {
            return Result.failure(Exception("导出包中没有找到对话文件"))
        }

        var totalSessions = 0
        var totalMessages = 0
        var skipped = 0

        for (name in sessionEntries) {
            val sessionId = File(name).nameWithoutExtension
            val entry = zip.getEntry(name) ?: continue
            val json = try {
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                skipped++; continue
            }
            val obj: JsonObject? = try {
                gson.fromJson(json, JsonObject::class.java)
            } catch (e: Exception) {
                null
            }
            if (obj == null) { skipped++; continue }

            val title = obj.get("title").asText()?.takeIf { it.isNotBlank() } ?: "导入的对话"
            val currentId = obj.get("current_message_id").asIntSafe()
            val msgsArray = obj.get("messages")?.let { if (it.isJsonArray) it.asJsonArray else null }
            if (msgsArray == null) { skipped++; continue }

            val messages = mutableListOf<ImportedMessage>()
            for (el in msgsArray) {
                if (!el.isJsonObject) { skipped++; continue }
                val m = el.asJsonObject
                val id = m.get("id").asIntSafe()
                val role = normalizeRole(m.get("role").asText())
                if (id == null || role == null) { skipped++; continue }
                messages.add(
                    ImportedMessage(
                        id = id,
                        parentId = m.get("parent_id").asIntSafe(),
                        role = role,
                        status = m.get("status").asText(),
                        insertedAt = parseTimestamp(m.get("inserted_at")),
                        thinkingEnabled = m.get("thinking_enabled").asBoolSafe(),
                        searchEnabled = m.get("search_enabled").asBoolSafe(),
                        fragments = parseFragments(
                            m.get("fragments")?.let { if (it.isJsonArray) it.asJsonArray else null }
                        )
                    )
                )
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
                    source = "imported",
                    // 导入的标题是网页版已确定的，禁止后续自动标题覆盖
                    titleEdited = true
                )
            )
            var mid = 0
            val entities = mutableListOf<MessageEntity>()
            for (m in flat) {
                // 过滤被内容过滤整轮（user + 紧邻 assistant CONTENT_FILTER）
                if (m.status.equals("CONTENT_FILTER", ignoreCase = true) ||
                    m.fragments.any { it.type == "CONTENT_FILTER" }
                ) continue
                val isError = m.status != null && !m.status.equals("FINISHED", ignoreCase = true)

                val body = StringBuilder()
                for (f in m.fragments) {
                    when {
                        // 正文白名单：CONTENT_RISK / TIP / TOOL_* 一律不当正文，避免混入风险提示
                        f.type in CONTENT_FRAGMENT_TYPES -> {
                            val c = f.content
                            if (!c.isNullOrEmpty()) body.append(c)
                        }
                        // 附件在导出包里是文件，APK 内没有实体，用占位文本标注，避免「内容凭空消失」
                        f.type == "FILE" -> {
                            for (n in f.fileNames) body.append("📎 ").append(n).append('\n')
                        }
                    }
                }
                var content = body.toString().trim()
                val thinking = m.fragments.filter { it.type == "THINK" }
                    .mapNotNull { it.content }.joinToString("\n\n").ifEmpty { null }
                val elapsed = m.fragments.firstOrNull { it.type == "THINK" && it.elapsed != null }?.elapsed

                if (content.isEmpty() && thinking.isNullOrEmpty() && !isError) continue
                if (content.isEmpty() && isError) content = "生成失败"

                mid++
                entities.add(
                    MessageEntity(
                        sessionId = localId,
                        messageId = mid,
                        parentId = if (mid > 1) mid - 1 else null,
                        role = m.role,
                        content = content,
                        thinking = thinking,
                        thinkingElapsed = elapsed,
                        thinkingEnabled = m.thinkingEnabled,
                        searchEnabled = m.searchEnabled,
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
        return Result.success(ImportResult(totalSessions, totalMessages, skipped))
    }

    private data class ImportedMessage(
        val id: Int,
        val parentId: Int?,
        val role: String,
        val status: String?,
        val insertedAt: Double,
        val thinkingEnabled: Boolean,
        val searchEnabled: Boolean,
        val fragments: List<Fragment>
    )

    private data class Fragment(
        val type: String,
        val content: String?,
        val elapsed: Double? = null,
        val fileNames: List<String> = emptyList()
    )

    // ---------- 安全 JSON 访问器：JsonNull / 缺失 / 类型漂移都不会抛异常 ----------

    private fun JsonElement?.asText(): String? = when {
        this == null || this.isJsonNull -> null
        this.isJsonPrimitive -> try { this.asJsonPrimitive.asString } catch (e: Exception) { null }
        else -> null
    }

    private fun JsonElement?.asIntSafe(): Int? = when {
        this == null || this.isJsonNull -> null
        this.isJsonPrimitive -> try {
            val p = this.asJsonPrimitive
            if (p.isNumber) p.asInt
            else p.asString.trim().toIntOrNull() ?: p.asString.trim().toDoubleOrNull()?.toInt()
        } catch (e: Exception) { null }
        else -> null
    }

    private fun JsonElement?.asDoubleSafe(): Double? = when {
        this == null || this.isJsonNull -> null
        this.isJsonPrimitive -> try {
            val p = this.asJsonPrimitive
            if (p.isNumber) p.asDouble else p.asString.trim().toDoubleOrNull()
        } catch (e: Exception) { null }
        else -> null
    }

    private fun JsonElement?.asBoolSafe(): Boolean = when {
        this == null || this.isJsonNull -> false
        this.isJsonPrimitive -> try {
            val p = this.asJsonPrimitive
            if (p.isBoolean) p.asBoolean else p.asString.equals("true", ignoreCase = true)
        } catch (e: Exception) { false }
        else -> false
    }

    /** 时间统一存 epoch 秒（double），兼容 ISO 字符串、秒、毫秒三种写法 */
    private fun parseTimestamp(el: JsonElement?): Double {
        if (el == null || el.isJsonNull || !el.isJsonPrimitive) return 0.0
        val p = el.asJsonPrimitive
        if (p.isNumber) {
            val v = try { p.asDouble } catch (e: Exception) { return 0.0 }
            return if (v > 1e11) v / 1000.0 else v   // 毫秒 → 秒
        }
        val s = try { p.asString.trim() } catch (e: Exception) { return 0.0 }
        if (s == "") return 0.0
        s.toDoubleOrNull()?.let { return it }
        for (pattern in ISO_PATTERNS) {
            try {
                val date = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(s)
                if (date != null) return date.time / 1000.0
            } catch (_: Exception) {}
        }
        return 0.0
    }

    private fun normalizeRole(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return when (raw.trim().uppercase(Locale.US)) {
            "USER", "HUMAN" -> "user"
            "ASSISTANT", "AI", "BOT", "MODEL" -> "assistant"
            "SYSTEM" -> "system"
            else -> raw.trim().lowercase(Locale.US)
        }
    }

    private fun parseFragments(arr: JsonArray?): List<Fragment> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val type = o.get("type").asText() ?: return@mapNotNull null
            val names = o.get("files")?.let { f ->
                if (f.isJsonArray) {
                    f.asJsonArray.mapNotNull { item ->
                        if (item.isJsonObject) item.asJsonObject.get("file_name").asText() else null
                    }
                } else emptyList()
            } ?: emptyList()
            Fragment(
                type = type,
                content = o.get("content").asText(),
                elapsed = o.get("elapsed_secs").asDoubleSafe(),
                fileNames = names
            )
        }
    }

    /** 沿 current_message_id 回溯到根，摊平为当前分支；链断时退回全部消息 */
    private fun flattenTree(messages: List<ImportedMessage>, currentId: Int?): List<ImportedMessage> {
        if (messages.isEmpty()) return emptyList()
        val byId = messages.associateBy { it.id }
        val branch = mutableSetOf<Int>()
        var cursor: Int? = currentId ?: messages.maxOf { it.id }
        while (cursor != null && byId.containsKey(cursor) && branch.add(cursor)) {
            cursor = byId[cursor]?.parentId
        }
        if (branch.isEmpty()) return messages.sortedBy { it.id }
        return messages.filter { it.id in branch }.sortedBy { it.id }
    }

    /** 导出所有本地会话为 zip（与网页版格式兼容，可被自身的导入器读回） */
    suspend fun exportAll(targetUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val sessions = dao.getSessions(Int.MAX_VALUE)
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                ZipOutputStream(out).use { zip ->
                    putEntry(zip, "manifest.json", "{\"export_format_version\":3}")
                    val index = sessions.map { mapOf("id" to it.id, "updated_at" to it.updatedAt / 1000.0) }
                    putEntry(zip, "chat_session_index.json", gson.toJson(index))
                    for (s in sessions) {
                        val msgs = dao.getMessages(s.id)
                        val obj = JsonObject().apply {
                            addProperty("title", s.title)
                            if (msgs.isNotEmpty()) addProperty("current_message_id", msgs.last().messageId)
                            val arr = JsonArray()
                            msgs.forEach { m ->
                                val mo = JsonObject().apply {
                                    addProperty("id", m.messageId)
                                    if (m.parentId != null) addProperty("parent_id", m.parentId)
                                    addProperty("role", m.role)
                                    addProperty("status", m.status)
                                    addProperty("inserted_at", m.insertedAt)
                                    val frags = JsonArray()
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
        /** 只有这些 fragment 算正文；CONTENT_RISK / TIP / TOOL_* 属于提示与联网元数据 */
        private val CONTENT_FRAGMENT_TYPES = setOf(
            "REQUEST", "RESPONSE", "TEMPLATE_RESPONSE", "CONTENT", "TEXT", "ANSWER"
        )
        private val ISO_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",   // 官方导出：2026-11-15T16:13:25.628Z
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss"
        )
    }
}
