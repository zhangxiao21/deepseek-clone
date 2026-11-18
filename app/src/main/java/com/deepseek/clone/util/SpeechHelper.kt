package com.deepseek.clone.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * 系统语音识别（离线可用取决于设备）：优先用 Android SpeechRecognizer 直接在 App 内收音，
 * 设备没有识别服务时由调用方降级到 ACTION_RECOGNIZE_SPEECH 的系统界面。
 * TTS 朗读由 TextToSpeech 提供。
 */
class SpeechHelper(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onStateCallback: ((Boolean) -> Unit)? = null

    fun isAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(context)
    } catch (_: Exception) { false }

    /** 构造系统语音识别 Intent（SpeechRecognizer 与 Activity 降级两条路径共用） */
    fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…")
    }

    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onState: (Boolean) -> Unit = {}
    ) {
        if (!isAvailable()) {
            onError("设备不支持应用内语音识别")
            return
        }
        onResultCallback = onResult
        onErrorCallback = onError
        onStateCallback = onState
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { onStateCallback?.invoke(true) }
                override fun onBeginningOfSpeech() { onStateCallback?.invoke(true) }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    onStateCallback?.invoke(false)
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到内容"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "语音识别网络异常"
                        else -> "语音识别失败 ($error)"
                    }
                    onErrorCallback?.invoke(msg)
                }
                override fun onResults(results: Bundle?) {
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
            onError("无法启动语音识别")
        }
    }

    /** 用户提前结束说话：让识别器用已收到的音频出结果 */
    fun stopListening() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
    }

    /** 彻底放弃本次识别（不回调结果） */
    fun cancelListening() {
        onResultCallback = null
        onErrorCallback = null
        try { recognizer?.cancel() } catch (_: Exception) {}
        onStateCallback?.invoke(false)
    }

    fun destroy() {
        onResultCallback = null
        onErrorCallback = null
        onStateCallback = null
        recognizer?.destroy()
        recognizer = null
    }
}
