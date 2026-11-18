package com.deepseek.clone.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * 语音输入。
 *
 * 国内 ROM 上「语音识别网络异常」的根因：默认的 SpeechRecognizer 连的是需要 Google 服务的
 * 在线识别服务，ERROR_NETWORK / ERROR_NETWORK_TIMEOUT 几乎必然出现。这里改成：
 *  1) Android 12+ 且有设备端离线识别包 → 用 createOnDeviceSpeechRecognizer，完全本地；
 *  2) 没有离线包 → 由调用方直接拉起 ACTION_RECOGNIZE_SPEECH 系统界面（多为厂商/输入法实现）；
 *  3) 只有退而求其次用在线 SpeechRecognizer 时才请求 EXTRA_PREFER_OFFLINE，
 *     一旦仍报网络错误就回调 onNeedFallback 自动切系统语音，不再把「网络异常」甩给用户。
 *
 * 注意：DeepSeek 官方开放 API 没有 ASR 端点，语音转文字只能依赖设备能力。
 */
class SpeechHelper(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onStateCallback: ((Boolean) -> Unit)? = null
    private var onFallbackCallback: (() -> Unit)? = null
    private var finished = false

    fun isAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(context)
    } catch (_: Exception) { false }

    /** Android 12+ 的设备端离线识别（无需联网，最可靠的一条路径） */
    fun hasOnDeviceRecognition(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            try { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
            catch (_: Exception) { false }

    /** 构造语音识别 Intent（SpeechRecognizer 与系统界面降级两条路径共用） */
    fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…")
        // 请求离线优先：即便走系统界面，也尽量用厂商本地语音包
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }

    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onState: (Boolean) -> Unit = {},
        onNeedFallback: () -> Unit = {}
    ) {
        val onDevice = hasOnDeviceRecognition()
        if (!onDevice && !isAvailable()) {
            onNeedFallback()
            return
        }
        finished = false
        onResultCallback = onResult
        onErrorCallback = onError
        onStateCallback = onState
        onFallbackCallback = onNeedFallback
        recognizer?.destroy()
        recognizer = try {
            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Exception) {
            Log.e("SpeechHelper", "create recognizer", e)
            onNeedFallback()
            return
        }.apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { onStateCallback?.invoke(true) }
                override fun onBeginningOfSpeech() { onStateCallback?.invoke(true) }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (finished) return
                    finished = true
                    when (error) {
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER -> {
                            // 在线服务不可用：自动切系统语音界面，不弹「网络异常」
                            onFallbackCallback?.invoke()
                        }
                        else -> {
                            onStateCallback?.invoke(false)
                            val msg = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到内容"
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音"
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别服务忙，请重试"
                                else -> "语音识别失败 ($error)"
                            }
                            onErrorCallback?.invoke(msg)
                        }
                    }
                }
                override fun onResults(results: Bundle?) {
                    if (finished) return
                    finished = true
                    onStateCallback?.invoke(false)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull().orEmpty()
                    if (text.isNotEmpty()) onResultCallback?.invoke(text)
                    else onErrorCallback?.invoke("没有识别到内容")
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        try {
            recognizer?.startListening(buildIntent())
        } catch (e: Exception) {
            Log.e("SpeechHelper", "startListening", e)
            onStateCallback?.invoke(false)
            onNeedFallback()
        }
    }

    /** 用户提前结束说话：让识别器用已收到的音频出结果 */
    fun stopListening() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
    }

    /** 彻底放弃本次识别（不回调结果） */
    fun cancelListening() {
        finished = true
        onResultCallback = null
        onErrorCallback = null
        try { recognizer?.cancel() } catch (_: Exception) {}
        onStateCallback?.invoke(false)
    }

    fun destroy() {
        finished = true
        onResultCallback = null
        onErrorCallback = null
        onStateCallback = null
        onFallbackCallback = null
        recognizer?.destroy()
        recognizer = null
    }
}
