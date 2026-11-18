package com.deepseek.clone.data.repository

import com.deepseek.clone.core.ApiConfig
import com.deepseek.clone.data.local.ChatDao
import com.deepseek.clone.data.local.MessageEntity
import com.deepseek.clone.data.local.SecureStore
import com.deepseek.clone.data.model.ChatMessage
import com.deepseek.clone.data.model.ChatRequest
import com.deepseek.clone.data.remote.DeepSeekApi
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 上下文工程：长期记忆 + 上下文压缩。
 *
 * 官方 API 无状态，每次请求都要把"该让模型知道的东西"重新发一遍。本类负责三件事：
 *  1) 长期记忆 Memory：跨会话的用户事实/偏好，注入 system prompt；每轮结束后用一次
 *     轻量非流式调用从对话里抽取新事实，去重合并。
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
        /** 长期记忆最多保留 N 条 */
        const val MAX_FACTS = 40
        /** 未触发压缩时允许回传的最大原文条数 */
        const val MAX_RAW_HISTORY = 60
        /** 记忆/摘要这类辅助非流式调用的超时（毫秒）：OkHttp readTimeout=0 永不超时，需自行兜底 */
        private const val AUX_TIMEOUT_MS = 25_000L
        private const val BASE_PERSONA = "You are a helpful AI assistant. Answer clearly and concisely in the user's language."
    }

    // ==================== 长期记忆 ====================

    fun memoryEnabled(): Boolean = store.memoryEnabled

    fun memoryFacts(): List<String> = readFacts()

    fun setMemoryFacts(list: List<String>) {
        store.memoryFactsJson = gson.toJson(list.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_FACTS))
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

    /** system prompt = 人设 + 长期记忆 + 上下文摘要 */
    fun buildSystemPrompt(contextSummary: String?): String = buildString {
        append(BASE_PERSONA)
        if (store.memoryEnabled) {
            val facts = readFacts()
            if (facts.isNotEmpty()) {
                append("\n\n# 关于用户的长期记忆\n")
                append("以下是跨会话积累的用户信息，请在相关时自然参考，不要生硬复述，也不要向用户声明记忆的存在。\n")
                facts.forEach { append("- ").append(it).append('\n') }
            }
        }
        if (!contextSummary.isNullOrBlank()) {
            append("\n\n# 更早对话的压缩摘要\n")
            append("以下是本次会话较早轮次的摘要，用于保持上下文连续性；最近的对话原文会附在后面。\n")
            append(contextSummary.trim())
        }
    }

    /**
     * 一轮对话结束后提取长期记忆。返回更新后的事实列表。
     * 仅在开关开启且双方内容非空时调用；失败返回原列表。
     */
    suspend fun learn(userText: String, assistantText: String): List<String> = withContext(Dispatchers.IO) {
        if (!store.memoryEnabled) return@withContext readFacts()
        if (userText.isBlank() || assistantText.isBlank()) return@withContext readFacts()

        val existing = readFacts()
        val instruction = """
你是「长期记忆提取器」。从下面这一轮对话中，抽取值得跨会话长期记住的【关于用户】的稳定信息，例如：身份/职业、明确偏好、长期目标、常用技术栈、反复出现的设定、重要决定。
不要抽取：一次性问题、寒暄、临时数据、助手自己的话、猜测。
已有记忆里已经出现过的内容不要重复。

严格只输出一个 JSON 字符串数组，例如 ["用户偏好简洁回答","用户是大二学生"]。若没有值得记住的新信息，输出 []。不要输出解释、Markdown 或代码块。

已有记忆（避免重复）：
${gson.toJson(existing)}
""".trimIndent()

        val input = "用户：${userText.take(1500)}\n\n助手：${assistantText.take(1500)}"
        val raw = oneShot(instruction, input, maxTokens = 300) ?: return@withContext existing
        val facts = parseFacts(raw)
        if (facts.isEmpty()) return@withContext existing

        val merged = (existing + facts).map { it.trim() }.distinct().take(MAX_FACTS)
        setMemoryFacts(merged)
        merged
    }

    private fun parseFacts(raw: String): List<String> {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        // 优先按 JSON 数组解析
        try {
            val list = gson.fromJson<List<String>>(
                cleaned, object : TypeToken<List<String>>() {}.type
            )
            if (list != null) return list.map { it.trim() }
                .filter { it.isNotEmpty() && it.length < 200 }
        } catch (_: Exception) { /* 退化到按行解析 */ }
        // 退化：逐行去掉列表符号/序号
        return cleaned.lines().mapNotNull { line ->
            line.trim()
                .removePrefix("- ").removePrefix("* ").removePrefix("• ")
                .replace(Regex("^\\d+[.、]\\s*"), "")
                .trim().trim('"', ',')
                .takeIf { it.isNotEmpty() && it.length < 200 && it != "[]" }
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
                // 注意 cached 可能残留上次开压缩时生成的摘要，关闭压缩后仍注入以保连续性。
                return@withContext cached to history.takeLast(MAX_RAW_HISTORY)
            }
            // 已触发压缩：较早轮次已有摘要兜底，只回传最近 KEEP_RECENT 条原文
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
