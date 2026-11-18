package com.deepseek.clone.data.remote

import com.deepseek.clone.data.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/** DeepSeek 官方开放接口（OpenAI 兼容）。认证头由 AuthInterceptor 统一注入。 */
interface DeepSeekApi {

    @POST("chat/completions")
    suspend fun chatCompletions(@Body request: ChatRequest): Response<ChatResponse>

    @Streaming
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
}
