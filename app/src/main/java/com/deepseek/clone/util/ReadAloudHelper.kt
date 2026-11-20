package com.deepseek.clone.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** 朗读助手：TextToSpeech 系统合成，中文优先。isSpeaking 供 UI 显示音波动画。 */
class ReadAloudHelper(context: Context) {
    private var tts: TextToSpeech? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        // 用局部引用接收回调，避免在属性初始化器中引用自身
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                val result = engine?.setLanguage(Locale.CHINA)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine?.setLanguage(Locale.US)
                }
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
                    override fun onDone(utteranceId: String?) { _isSpeaking.value = false }
                    override fun onError(utteranceId: String?) { _isSpeaking.value = false }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        _isSpeaking.value = false
                    }
                })
            }
        }
    }

    fun speak(text: String) {
        // 去除 markdown 标记后再朗读
        val clean = text.replace(Regex("```.*?```"), "代码块")
            .replace(Regex("[`*#>\\[\\]]"), "")
            .take(4000)
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "ds_tts")
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun destroy() {
        tts?.shutdown()
        tts = null
        _isSpeaking.value = false
    }
}
