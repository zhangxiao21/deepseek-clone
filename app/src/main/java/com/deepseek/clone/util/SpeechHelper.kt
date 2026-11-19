package com.deepseek.clone.util

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 端侧离线语音转文字（Vosk）。
 *
 * 彻底替代依赖系统/厂商 SpeechRecognizer 的方案——后者在国产 ROM 上「语音识别网络异常」频发，
 * 且离线包缺失时几乎不可用。Vosk 完全在本地推理：
 *  1) 首次启动把打包在 assets 的中文模型解压到 filesDir；
 *  2) AudioRecord 采集 16kHz / 单声道 / PCM16 音频；
 *  3) 送入 Vosk Recognizer 流式识别，支持部分结果回调 + 静音自动结束。
 *
 * 无需任何网络，语音数据全程留在设备本地。
 *
 * 模型目录约定：assets/vosk-model-small-cn/（解压后含 am/conf/graph/ivector 等子目录），
 * 见 README「端侧语音模型」一节。
 */
class SpeechHelper(private val context: Context) {

    companion object {
        private const val TAG = "SpeechHelper"
        /** 采样率：中文小模型固定 16kHz */
        private const val SAMPLE_RATE = 16000
        /** assets 中的模型目录名（与解压目标目录名解耦） */
        private const val MODEL_ASSET_DIR = "vosk-model-small-cn"
        /** 解压到 filesDir 下的目标目录名 */
        private const val MODEL_DEST_DIR = "vosk-model-small-cn"
        /** 单帧音频字节数（约 0.1s） */
        private const val FRAME_BYTES = 3200
        /** 无语音静音后自动结束的连续静音帧数（约 1.2s） */
        private const val SILENCE_FRAMES_TO_STOP = 12
        /** 简单 RMS 静音阈值 */
        private const val SILENCE_RMS = 800.0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    private var onResult: ((String) -> Unit)? = null
    private var onPartial: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onState: ((Boolean) -> Unit)? = null

    /** 模型是否已加载完成 */
    @Volatile
    private var modelReady = false

    /** 是否正在初始化（避免重复触发） */
    private val initializing = AtomicBoolean(false)

    fun isModelReady(): Boolean = modelReady

    /**
     * 异步加载模型（幂等）。首次调用把 assets 里的模型递归复制到 filesDir，之后直接复用。
     * @param onDone 加载完成回调（成功 true / 失败 false）
     */
    fun initModel(onDone: (Boolean) -> Unit) {
        if (modelReady) {
            onDone(true)
            return
        }
        if (!initializing.compareAndSet(false, true)) {
            // 已有初始化在进行中；UI 侧可在稍后就绪后重试
            onDone(false)
            return
        }
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    LibVosk.setLogLevel(LogLevel.WARNINGS)
                    val destDir = File(context.filesDir, MODEL_DEST_DIR)
                    if (!File(destDir, "am").exists()) {
                        destDir.mkdirs()
                        copyAssets(context, MODEL_ASSET_DIR, destDir)
                    }
                    val m = Model(destDir.absolutePath)
                    model = m
                    m
                }
                modelReady = true
                onDone(true)
            } catch (e: Exception) {
                Log.e(TAG, "initModel", e)
                modelReady = false
                onDone(false)
            } finally {
                initializing.set(false)
            }
        }
    }

    /** 递归复制 assets 目录到目标目录 */
    private fun copyAssets(ctx: Context, assetPath: String, dest: File) {
        val list = ctx.assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            // 是文件
            ctx.assets.open(assetPath).use { input ->
                File(dest, assetPath.substringAfterLast('/')).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }
        for (name in list) {
            copyAssets(ctx, "$assetPath/$name", dest)
        }
    }

    /**
     * 开始监听。要求先 initModel 成功。
     */
    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onState: (Boolean) -> Unit = {},
        onPartial: (String) -> Unit = {}
    ) {
        val m = model
        if (m == null) {
            onError("语音模型尚未就绪，请稍候重试")
            return
        }
        if (running.get()) return

        this.onResult = onResult
        this.onPartial = onPartial
        this.onError = onError
        this.onState = onState

        running.set(true)
        onState(true)
        worker = Thread({ recordLoop(m) }, "vosk-asr").apply { start() }
    }

    /** 用户主动结束说话：停止采集，让已收到的音频出最终结果 */
    fun stopListening() {
        running.set(false)
        runCatching { audioRecord?.stop() }
        worker?.interrupt()
    }

    /** 彻底放弃本次识别（不回调结果） */
    fun cancelListening() {
        running.set(false)
        onResult = null
        onPartial = null
        onError = null
        runCatching { audioRecord?.stop() }
        worker?.interrupt()
    }

    fun destroy() {
        cancelListening()
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
        modelReady = false
    }

    // ==================== 采集 + 识别 ====================

    private fun recordLoop(m: Model) {
        var rec: Recognizer? = null
        var recorder: AudioRecord? = null
        try {
            rec = Recognizer(m, SAMPLE_RATE.toFloat())
            recognizer = rec

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf * 2, FRAME_BYTES)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IOException("麦克风初始化失败")
            }
            audioRecord = recorder
            recorder.startRecording()

            val buffer = ByteArray(FRAME_BYTES)
            var silenceFrames = 0
            var gotSpeech = false

            while (running.get() && !Thread.currentThread().isInterrupted) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue

                val rms = rms(buffer, read)
                if (rms > SILENCE_RMS) {
                    silenceFrames = 0
                    gotSpeech = true
                } else if (gotSpeech) {
                    silenceFrames++
                    if (silenceFrames >= SILENCE_FRAMES_TO_STOP) {
                        break // 静音超时，自动结束
                    }
                }

                if (rec.acceptWaveForm(buffer, read)) {
                    val text = parseText(rec.result)
                    if (text.isNotEmpty()) onPartial?.invoke(text)
                } else {
                    val partial = parseText(rec.partialResult)
                    if (partial.isNotEmpty()) onPartial?.invoke(partial)
                }
            }

            // 收尾：拿到最终结果
            val final = parseText(rec.finalResult)
            if (final.isNotEmpty()) onResult?.invoke(final)
            else onError?.invoke("没有识别到内容")
        } catch (e: Exception) {
            if (running.get() && !Thread.currentThread().isInterrupted) {
                Log.e(TAG, "recordLoop", e)
                onError?.invoke(e.message ?: "语音识别失败")
            }
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            audioRecord = null
            recognizer = null
            runCatching { rec?.close() }
            running.set(false)
            onState?.invoke(false)
        }
    }

    /** Vosk 返回 JSON {"text": "..."}，抽取 text 字段 */
    private fun parseText(json: String): String = try {
        JSONObject(json).optString("text", "").trim()
    } catch (_: Exception) { "" }

    /** 简单 RMS（均方根）估算响度，用于静音检测 */
    private fun rms(buffer: ByteArray, len: Int): Double {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i < len - 1) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            sum += (sample * sample).toDouble()
            count++
            i += 2
        }
        if (count == 0) return 0.0
        return kotlin.math.sqrt(sum / count)
    }
}
