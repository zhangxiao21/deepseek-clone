package com.deepseek.clone.data.remote

import com.deepseek.clone.data.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.*

/** DeepSeek 官方开放接口（OpenAI 兼容）。认证头由 AuthInterceptor 统一注入。 */
interface DeepSeekApi {

    @POST("chat/completions")
    suspend fun chatCompletions(@Body request: ChatRequest): Response<ChatResponse>

    @Streaming
    // SSE 专用头：声明事件流、禁用 gzip 透明压缩（否则中间层会缓冲整个响应，
    // 表现为「全部生成完才一次性输出」）
    @Headers(
        "Accept: text/event-stream",
        "Cache-Control: no-cache",
        "Accept-Encoding: identity"
    )
    @POST("chat/completions")
    suspend fun chatCompletionsStream(@Body request: ChatRequest): Response<ResponseBody>

    @GET("models")
    suspend fun listModels(): Response<ModelListResponse>

    @Multipart
    @POST("files")
    suspend fun uploadFile(
        @Part("purpose") purpose: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<FileObject>

    /** 官方 chat-demo 同款 ASR：multipart 上传 WAV/Ogg，一次返回 {"text": "..."}。同步调用（工作线程内 .execute()）。 */
    @Multipart
    @POST("v1/tools/asr")
    fun transcribe(@Part file: MultipartBody.Part): Call<AsrResponse>
}
