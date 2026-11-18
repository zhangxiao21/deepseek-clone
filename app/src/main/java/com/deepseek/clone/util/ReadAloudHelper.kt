package com.deepseek.clone.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

/** 朗读助手：TextToSpeech 系统合成，中文优先 */
class ReadAloudHelper(context: Context) {
    private var tts: TextToSpeech? = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.CHINA)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
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

    fun stop() { tts?.stop() }

    fun destroy() { tts?.shutdown(); tts = null }
}
