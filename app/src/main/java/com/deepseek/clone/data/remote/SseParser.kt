package com.deepseek.clone.data.remote

import com.deepseek.clone.data.model.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.ResponseBody
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 标准 OpenAI 兼容 SSE 流解析（DeepSeek 官方 API）。
 *
 * 每个 chunk:
 *   data: {"choices":[{"delta":{"content":"你","reasoning_content":"..."}}]}
 *   ...
 *   data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{...}}
 *   data: [DONE]
 *
 * 思考内容在 delta.reasoning_content；联网引用在最后一个 chunk 的 delta.annotations；
 * 服务端工具过程在 delta.server_tool_event（每个 chunk 最多一个）。
 */
object SseParser {

    private val gson = Gson()

    data class StreamResult(
        val thinking: StringBuilder = StringBuilder(),
        val content: StringBuilder = StringBuilder(),
        val citations: MutableList<UrlCitation> = mutableListOf(),
        val toolEvents: MutableList<ServerToolEvent> = mutableListOf(),
        var finished: Boolean = false,
        var finishReason: String? = null,
        var title: String? = null,
        var error: String? = null,
        var usage: Usage? = null
    )

    sealed class Event {
        data class ThinkingDelta(val text: String) : Event()
        data class ContentDelta(val text: String) : Event()
        data class ToolEvent(val event: ServerToolEvent) : Event()
        data class Citations(val list: List<UrlCitation>) : Event()
        data class Title(val title: String) : Event()
        data class Finished(val result: StreamResult) : Event()
        data class Error(val message: String) : Event()
    }

    /** 解析 SSE 字节流。body 由调用方在 finally 中关闭。 */
    fun parse(body: ResponseBody): Flow<Event> = flow {
        val result = StreamResult()
        BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: break
                if (!l.startsWith("data:")) continue
                val data = l.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") {
                    result.finished = true
                    break
                }
                try {
                    val chunk = gson.fromJson(data, ChatChunk::class.java) ?: continue
                    chunk.usage?.let { result.usage = it }
                    for (choice in chunk.choices) {
                        choice.finishReason?.let {
                            result.finishReason = it
                            result.finished = true
                        }
                        val d = choice.delta
                        d.content?.let {
                            result.content.append(it)
                            emit(Event.ContentDelta(it))
                        }
                        d.reasoningContent?.let {
                            result.thinking.append(it)
                            emit(Event.ThinkingDelta(it))
                        }
                        d.serverToolEvent?.let { ev ->
                            result.toolEvents.add(ev)
                            emit(Event.ToolEvent(ev))
                        }
                        d.annotations?.forEach { ann ->
                            ann.urlCitation?.let { result.citations.add(it) }
                        }
                        d.title?.let {
                            result.title = it
                            emit(Event.Title(it))
                        }
                        // 某些实现把最终引用放在 message 而非 delta
                        choice.message?.annotations?.forEach { ann ->
                            ann.urlCitation?.let { result.citations.add(it) }
                        }
                    }
                } catch (e: Exception) {
                    emit(Event.Error("解析流数据失败：${e.message}"))
                }
            }
        }
        if (result.citations.isNotEmpty()) emit(Event.Citations(result.citations))
        if (!result.finished) result.finished = true
        emit(Event.Finished(result))
    }
}
