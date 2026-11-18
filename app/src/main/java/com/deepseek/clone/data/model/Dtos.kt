package com.deepseek.clone.data.model

import com.google.gson.annotations.SerializedName

// ==================== Chat Completions API (OpenAI 兼容) ====================

/** 请求消息。content 可以是纯文本字符串，或多模态 part 数组。这里用 Any 承载两者。 */
data class ChatMessage(
    val role: String,                 // system / user / assistant / tool
    val content: Any,                 // String 或 List<ContentPart>
    val name: String? = null
)

/** 多模态内容块 */
data class ContentPart(
    val type: String,                 // text / image_url / file
    val text: String? = null,
    @SerializedName("image_url") val imageUrl: ImageUrl? = null,
    @SerializedName("file_id") val fileId: String? = null,
    @SerializedName("file_data") val fileData: String? = null,
    val filename: String? = null
)

data class ImageUrl(
    val url: String,                  // http(s):// 或 data:image/...;base64,...
    val detail: String? = null        // low / high / original / auto
)

/** 内置联网工具（无需 parameters） */
data class BuiltinTool(val type: String, val name: String)

/** 聊天请求 */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    @SerializedName("stream_options") val streamOptions: StreamOptions? = null,
    val thinking: Thinking? = null,
    @SerializedName("reasoning_effort") val reasoningEffort: String? = null,
    val tools: List<BuiltinTool>? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    val temperature: Float? = null,
    @SerializedName("top_p") val topP: Float? = null,
    val title: TitleOption? = null,
    @SerializedName("user_id") val userId: String? = null
)

data class StreamOptions(@SerializedName("include_usage") val includeUsage: Boolean = true)
data class Thinking(val type: String)         // enabled / disabled
data class TitleOption(val enabled: Boolean = true)

/** 非流式响应 */
data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null
)

data class Choice(
    val index: Int = 0,
    val message: ResponseMessage = ResponseMessage(),
    @SerializedName("finish_reason") val finishReason: String? = null
)

data class ResponseMessage(
    val role: String? = "assistant",
    val content: String? = null,
    @SerializedName("reasoning_content") val reasoningContent: String? = null,
    val annotations: List<Annotation>? = null,
    @SerializedName("server_tool_calls") val serverToolCalls: List<ServerToolStep>? = null,
    val title: String? = null,
    val refusal: String? = null
)

data class Annotation(
    val type: String? = null,
    @SerializedName("url_citation") val urlCitation: UrlCitation? = null
)

data class UrlCitation(
    val title: String? = null,
    val url: String? = null,
    @SerializedName("start_index") val startIndex: Int? = null,
    @SerializedName("end_index") val endIndex: Int? = null
)

data class ServerToolStep(val calls: List<ServerToolCall>? = null)
data class ServerToolCall(
    val kind: String? = null,          // search / deep_search / read_link / image_search
    val status: String? = null,
    val action: String? = null
)

data class Usage(
    @SerializedName("prompt_tokens") val promptTokens: Int? = null,
    @SerializedName("completion_tokens") val completionTokens: Int? = null,
    @SerializedName("total_tokens") val totalTokens: Int? = null
)

/** SSE 单个 chunk */
data class ChatChunk(
    val id: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
    val usage: Usage? = null
)

data class ChunkChoice(
    val index: Int = 0,
    val delta: Delta = Delta(),
    @SerializedName("finish_reason") val finishReason: String? = null,
    val message: ResponseMessage? = null
)

data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerializedName("reasoning_content") val reasoningContent: String? = null,
    val annotations: List<Annotation>? = null,
    @SerializedName("server_tool_calls") val serverToolCalls: List<ServerToolStep>? = null,
    @SerializedName("server_tool_event") val serverToolEvent: ServerToolEvent? = null,
    val title: String? = null
)

data class ServerToolEvent(
    val kind: String? = null,
    val status: String? = null,
    val action: String? = null
)

// ==================== Models API ====================

data class ModelListResponse(val data: List<ModelInfo> = emptyList())
data class ModelInfo(
    val id: String,
    val name: String? = null,
    @SerializedName("context_window") val contextWindow: Long? = null
)

// ==================== Files API ====================

data class FileObject(
    val id: String,
    val filename: String? = null,
    val purpose: String? = null,
    val bytes: Long? = null,
    val status: String? = null
)
