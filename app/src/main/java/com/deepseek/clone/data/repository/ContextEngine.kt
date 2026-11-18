package com.deepseek.clone.data.repository

import com.deepseek.clone.core.ApiConfig
import com.deepseek.clone.data.local.ChatDao
import com.deepseek.clone.data.local.MessageEntity
import com.deepseek.clone.data.local.SecureStore
import com.deepseek.clone.data.model.ChatMessage
import com.deepseek.clone.data.model.ChatRequest
import com.deepseek.clone.data.remote.DeepSeekApi
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 上下文工程：长期记忆 + 上下文压缩。
 *
 * 官方 API 无状态，每次请求都要把"该让模型知道的东西"重新发一遍。本类负责三件事：
 *  1) 长期记忆 Memory：跨会话的用户事实/偏好，注入 system prompt；每轮结束后用一次
 *     轻量非流式调用，结合最近多轮上下文抽取新事实，去重合并。
 *  2) 上下文压缩 Compression：历史超过阈值时，把较旧的轮次压成摘要注入 system prompt，
 *     只回传最近若干轮原文。摘要按会话缓存并增量更新，避免每轮重复压缩。
 *  3) System Prompt 组装。
 *
 * 设计原则：所有额外网络调用都是「尽力而为」，失败一律静默降级，
 * 绝不抛异常阻断主对话链路（用户宁可少一点记忆，也不能发不出消息）。
 */
class ContextEngine(
    private val api: DeepSeekApi,
    private val store: SecureStore,
    private val dao: ChatDao
) {
    private val gson = Gson()

    companion object {
        /** 保留最近 N 条消息原文回传 */
        const val KEEP_RECENT = 24
        /** 历史超过 N 条才触发压缩 */
        const val COMPRESS_TRIGGER = 30
        /** 长期记忆最多保留 N 条（旧版 40 条太容易丢信息） */
        const val MAX_FACTS = 120
        /** 未触发压缩时允许回传的最大原文条数 */
        const val MAX_RAW_HISTORY = 60
        /** 单轮记忆提炼带入的前文条数，用于理解指代与语境 */
        const val MEMORY_CONTEXT_TURNS = 6
        /** 手动重提炼时扫描的会话数与每个会话的消息数 */
        const val REBUILD_SESSIONS = 8
        const val REBUILD_MESSAGES_PER_SESSION = 12
        private const val MEMORY_MAX_TOKENS = 900
        /** 记忆/摘要这类辅助非流式调用的超时（毫秒）：OkHttp readTimeout=0 永不超时，需自行兜底 */
        private const val AUX_TIMEOUT_MS = 40_000L
        private const val BASE_PERSONA = "You are a helpful AI assistant. Answer clearly and concisely in the user's language."
    }

    // ==================== 长期记忆 ====================

    fun memoryEnabled(): Boolean = store.memoryEnabled

    fun memoryFacts(): List<String> = readFacts()

    fun setMemoryFacts(list: List<String>) {
        store.memoryFactsJson = gson.toJson(mergeFacts(emptyList(), list))
    }

    fun clearMemory() {
        store.memoryFactsJson = "[]"
    }

    private fun readFacts(): List<String> = try {
        gson.fromJson<List<String>>(store.memoryFactsJson, object : TypeToken<List<String>>() {}.type)
            ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** 去重（按去标点空白后的小写形式），超出上限时保留最新的事实而不是丢掉新抽取的 */
    private fun mergeFacts(existing: List<String>, incoming: List<String>): List<String> {
        val seen = HashSet<String>()
        val merged = ArrayList<String>()
        (existing + incoming).forEach { raw ->
            val fact = raw.trim().trim('"', ',', '，')
                .replace(Regex("\\s+"), " ")
            if (fact.isEmpty() || fact.length > 300) return@forEach
            val norm = fact.lowercase().filter { !it.isWhitespace() && it !in "，。,.、；;：:！!？?\"'「」" }
            if (norm.length < 4) return@forEach
            if (seen.add(norm)) merged.add(fact)
        }
        // 旧的在前、新的在后；容量满时优先留新事实
        return if (merged.size > MAX_FACTS) merged.takeLast(MAX_FACTS) else merged
    }

    /** system prompt = 人设 + 长期记忆 + 上下文摘要 */
    fun buildSystemPrompt(contextSummary: String?): String = buildString {
        append(BASE_PERSONA)
        if (store.memoryEnabled) {
            val facts = readFacts()
            if (facts.isNotEmpty()) {
                append("\n\n# 关于用户的长期记忆\n")
                append("以下是跨会话积累的用户信息。回答时应主动据此调整措辞、深度、格式与推荐，")
                append("但不要生硬复述，也不要向用户声明记忆的存在；若与当前问题冲突，以用户本轮说法为准。\n")
                facts.forEach { append("- ").append(it).append('\n') }
            }
        }
        if (!contextSummary.isNullOrBlank()) {
            append("\n\n# 更早对话的压缩摘要\n")
            append("以下是本次会话较早轮次的摘要，用于保持上下文连续性；最近的对话原文会附在后面。\n")
            append(contextSummary.trim())
        }
    }

    /** 生成记忆提炼指令（单轮自动提炼与手动批量重提炼共用） */
    private fun memoryInstruction(existing: List<String>): String = """
你是「长期记忆提取器」。请从对话记录中抽取值得【跨会话长期记住】的、关于用户本人的稳定信息。

必须覆盖以下类别（出现就要抽取，没出现不必硬凑）：
- 身份与背景：职业、年级/专业、所在城市、所在行业、团队角色
- 明确偏好：回答语言、详略、格式（表格/分点/代码注释）、语气、常用称呼
- 能力与工具：技术栈、编程语言、框架、设备、常用软件与模型
- 长期目标与项目：正在做的项目、目标、时间节点、反复出现的设定与约束
- 重要决定与结论：已拍板的方案、明确的选择、不打算做的事
- 人际与称呼：需要长期记住的人名、关系、称呼习惯
- 明确禁忌：不希望出现的内容或做法

要求：
1. 每条必须是完整、可独立理解的短句，主语统一写「用户」，例如「用户偏好用表格对比方案」。
2. 尽量具体，保留专有名词、数字与技术名词；不要写「用户问了一个问题」这种废话。
3. 不要抽取：一次性问题、寒暄、临时数据、助手自己的观点、你的猜测、能从当轮直接看出的内容。
4. 与已有记忆语义重复的内容不要输出；但同一事实有更新时，输出更新后的版本。
5. 最多输出 12 条。严格只输出一个 JSON 字符串数组，例如 ["用户偏好简洁回答","用户是 Android 开发者"]；
   没有值得记住的新信息就输出 []。不要输出解释、标题、Markdown 或代码块。

已有记忆（避免重复）：
${gson.toJson(existing)}
""".trimIndent()

    /** 把若干条消息拼成提炼用的对话记录 */
    private fun transcript(messages: List<MessageEntity>, userText: String, assistantText: String): String =
        buildString {
            messages.takeLast(MEMORY_CONTEXT_TURNS).forEach { m ->
                val who = if (m.role == "user") "用户" else "助手"
                append(who).append("：")
                append(m.content.replace('\n', ' ').take(700))
                append('\n')
            }
            append("用户：").append(userText.replace('\n', ' ').take(1200)).append("\n\n")
            append("助手：").append(assistantText.replace('\n', ' ').take(1200))
        }.take(7000)

    /**
     * 一轮对话结束后提取长期记忆。返回更新后的事实列表。
     * 带最近若干轮历史，避免「它/那个项目」这类指代导致漏记。
     */
    suspend fun learn(
        history: List<MessageEntity>,
        userText: String,
        assistantText: String
    ): List<String> = withContext(Dispatchers.IO) {
        if (!store.memoryEnabled) return@withContext readFacts()
        if (userText.isBlank() && assistantText.isBlank()) return@withContext readFacts()

        val existing = readFacts()
        val raw = oneShot(
            memoryInstruction(existing),
            transcript(history, userText, assistantText),
            MEMORY_MAX_TOKENS
        ) ?: return@withContext existing
        val facts = parseFacts(raw)
        if (facts.isEmpty()) return@withContext existing

        val merged = mergeFacts(existing, facts)
        setMemoryFacts(merged)
        merged
    }

    /**
     * 手动「重新提炼」：扫描最近若干会话的历史，分批抽取后合并。
     * 用于补齐早期版本抽取不充分漏掉的信息；失败返回当前已有记忆。
     */
    suspend fun rebuildFromHistory(all: List<MessageEntity>): List<String> = withContext(Dispatchers.IO) {
        if (!store.memoryEnabled) return@withContext readFacts()
        val usable = all.filter { it.content.isNotBlank() && !it.isError }
        if (usable.isEmpty()) return@withContext readFacts()

        var merged = readFacts()
        usable.chunked(REBUILD_MESSAGES_PER_SESSION).take(6).forEach { chunk ->
            val text = buildString {
                chunk.forEach { m ->
                    val who = if (m.role == "user") "用户" else "助手"
                    append(who).append("：").append(m.content.replace('\n', ' ').take(700)).append('\n')
                }
            }.take(7000)
            val raw = oneShot(memoryInstruction(merged), text, MEMORY_MAX_TOKENS)
            if (raw != null) {
                val facts = parseFacts(raw)
                if (facts.isNotEmpty()) merged = mergeFacts(merged, facts)
            }
        }
        setMemoryFacts(merged)
        merged
    }

    private fun parseFacts(raw: String): List<String> {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()
        try {
            // 兼容模型偶尔返回 {"facts": [...]} 或 {"memory": [...]} 的对象形式
            val obj = gson.fromJson(cleaned, JsonObject::class.java)
            if (obj != null && obj.has("facts")) {
                val arr = obj.getAsJsonArray("facts")
                if (arr != null) return arr.mapNotNull { runCatching { it.asString }.getOrNull() }
                    .map { it.trim() }.filter { it.isNotEmpty() }
            }
        } catch (_: Exception) { /* 不是对象，按数组解析 */ }
        try {
            val list = gson.fromJson<List<String>>(
                cleaned, object : TypeToken<List<String>>() {}.type
            )
            if (list != null) return list.map { it.trim() }
                .filter { it.isNotEmpty() && it.length < 300 }
        } catch (_: Exception) { /* 退化到按行解析 */ }
        // 退化：逐行去掉列表符号/序号/引号
        return cleaned.lines().mapNotNull { line ->
            line.trim()
                .removePrefix("- ").removePrefix("* ").removePrefix("• ")
                .replace(Regex("^\\d+[.、]\\s*"), "")
                .trim().trim('"', ',', '[', ']')
                .takeIf { it.isNotEmpty() && it.length < 300 && it != "[]" }
        }
    }

    // ==================== 上下文压缩 ====================

    /**
     * 准备本轮要回传的历史。纯本地操作（读缓存 + 裁剪），不发起网络请求，保证首 token 不被拖慢。
     * @return Pair(压缩摘要, 需要回传的近期历史)
     */
    suspend fun prepareHistory(sessionId: String, history: List<MessageEntity>): Pair<String?, List<MessageEntity>> =
        withContext(Dispatchers.IO) {
            val session = dao.getSession(sessionId)
            val cached = session?.contextSummary
            if (!store.compressionEnabled || history.size <= COMPRESS_TRIGGER) {
                // 未触发压缩：回传全部近期原文（最多 MAX_RAW_HISTORY 条），不做无补偿的截断。
                return@withContext cached to history.takeLast(MAX_RAW_HISTORY)
            }
            cached to history.takeLast(KEEP_RECENT)
        }

    /**
     * 后台增量压缩：把「缓存摘要之后、最近 KEEP_RECENT 条之前」的新增旧消息压成摘要并缓存。
     * 供下一轮请求使用（压缩结果滞后一轮，是刻意的：不阻塞当前对话）。
     */
    suspend fun compressInBackground(sessionId: String, history: List<MessageEntity>) = withContext(Dispatchers.IO) {
        if (!store.compressionEnabled || history.size <= COMPRESS_TRIGGER) return@withContext
        val session = dao.getSession(sessionId) ?: return@withContext
        val older = history.dropLast(KEEP_RECENT)
        if (older.isEmpty()) return@withContext
        val fresh = older.filter { it.messageId > session.summaryUpToMessageId }
        if (fresh.isEmpty()) return@withContext

        val generated = requestSummary(session.contextSummary, fresh) ?: return@withContext
        dao.updateSummary(sessionId, generated, older.last().messageId)
    }

    private suspend fun requestSummary(previous: String?, chunk: List<MessageEntity>): String? {
        val transcript = buildString {
            chunk.forEach { m ->
                val who = if (m.role == "user") "用户" else "助手"
                append(who).append("：")
                append(m.content.replace('\n', ' ').take(500))
                append('\n')
            }
        }.take(6000)

        val instruction = buildString {
            append("你是「上下文压缩器」。请把下面的对话记录压缩成一份简洁的中文摘要。\n")
            append("必须保留：用户提出的目标与约束、已确认的事实与数据、做出的决定、重要结论、未完成事项、涉及的专有名词与文件名。\n")
            append("丢弃：寒暄、重复表述、冗长推理过程。\n")
            append("只输出摘要正文，不要输出解释、标题或 Markdown 代码块，控制在 600 字以内。\n")
            if (!previous.isNullOrBlank()) {
                append("\n这是已有摘要，请在此基础上合并更新（去重、保留最新结论）：\n")
                append(previous.take(1200))
            }
        }
        return oneShot(instruction.toString(), transcript, maxTokens = 700)
    }

    /** 清除某会话已缓存的压缩摘要（用户清空上下文时用） */
    suspend fun clearSummary(sessionId: String) = withContext(Dispatchers.IO) {
        dao.updateSummary(sessionId, null, 0)
    }

    // ==================== 非流式轻量调用 ====================

    private suspend fun oneShot(instruction: String, input: String, maxTokens: Int): String? = try {
        val request = ChatRequest(
            model = ApiConfig.MODEL_FLASH,
            messages = listOf(
                ChatMessage("system", instruction),
                ChatMessage("user", input)
            ),
            stream = false,
            maxTokens = maxTokens
        )
        withTimeout(AUX_TIMEOUT_MS) {
            val resp = api.chatCompletions(request)
            if (!resp.isSuccessful) null
            else resp.body()?.choices?.firstOrNull()?.message?.content?.trim()?.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        // 超时/网络错误/解析失败一律静默降级：辅助功能绝不阻断主对话
        null
    }
}
