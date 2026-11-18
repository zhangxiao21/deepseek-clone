package com.deepseek.clone.data.remote

import com.deepseek.clone.core.ApiConfig
import com.deepseek.clone.core.ClientProfile
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** 注入官方 API 的 Bearer API Key。保留 ClientProfile 以兼容设置页（现仅 userAgent 有意义）。 */
class AuthInterceptor(
    private val apiKeyProvider: () -> String?,
    private val profileProvider: () -> ClientProfile
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .header("User-Agent", profileProvider().userAgent)
        // 流式请求自带 Accept: text/event-stream，不能被覆盖成 application/json
        if (original.header("Accept") == null) {
            builder.header("Accept", "application/json")
        }
        // 官方接口：Bearer API Key（sk-...）
        apiKeyProvider()?.let { builder.header("Authorization", "Bearer $it") }
        // multipart 自带带 boundary 的 Content-Type，不覆盖
        if (original.header("Content-Type") == null) {
            builder.header("Content-Type", "application/json")
        }
        // 不手动设 Accept-Encoding：OkHttp 自动 gzip 并透明解压
        return chain.proceed(builder.build())
    }
}

/** 401/402/403 视为密钥失效或额度不足 */
class ApiErrorInterceptor(private val onAuthError: (String) -> Unit) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val resp = chain.proceed(chain.request())
        when (resp.code) {
            401 -> onAuthError("API Key 无效，请重新输入")
            402 -> onAuthError("账户余额不足，请充值")
            403 -> onAuthError("无权限访问该模型")
        }
        return resp
    }
}

object NetworkModule {

    @Volatile
    private var apiKeyProvider: () -> String? = { null }
    @Volatile
    private var profileProvider: () -> ClientProfile = { ClientProfile() }
    @Volatile
    private var onAuthError: (String) -> Unit = {}

    fun configure(
        apiKeyProvider: () -> String?,
        profileProvider: () -> ClientProfile,
        onAuthError: (String) -> Unit
    ) {
        this.apiKeyProvider = apiKeyProvider
        this.profileProvider = profileProvider
        this.onAuthError = onAuthError
    }

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)   // SSE 长连接
            .writeTimeout(120, TimeUnit.SECONDS)     // 文件上传
            .pingInterval(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor({ apiKeyProvider() }, { profileProvider() }))
            .addInterceptor(ApiErrorInterceptor { onAuthError(it) })
            .retryOnConnectionFailure(true)
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val api: DeepSeekApi by lazy { retrofit.create(DeepSeekApi::class.java) }
    fun client(): OkHttpClient = okHttp
}
