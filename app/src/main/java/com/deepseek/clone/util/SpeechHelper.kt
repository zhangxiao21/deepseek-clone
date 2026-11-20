package com.deepseek.clone.util

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.deepseek.clone.data.remote.NetworkModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音转文字：调用官方 ASR API（对齐官方 chat-demo）。
 *
 * 官方 demo 的做法（src/services/api/speech.ts + speech/recorder.ts）：
 *  - 录音为 16kHz / 单声道 / 16bit PCM 的 WAV（≤120s、≤8MiB）；
 *  - POST /v1/tools/asr，multipart 上传，Authorization: Bearer API Key；
 *  - 一次返回 {"text": "..."}。
 *
 * 这样识别准确率由官方服务端保证，无需本地大模型。语音数据随请求发给 API 服务。
 */
class SpeechHelper(private val context: Context) {

    companion object {
        private const val TAG = "SpeechHelper"
        private const val SAMPLE_RATE = 16000
        /** ASR 单次时长上限（与官方 demo 一致） */
        private const val MAX_MS = 120_000
        /** 大小上限 8 MiB */
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val FRAME_BYTES = 3200
        /** 静音判定帧数（约 1.5s） */
        private const val SILENCE_FRAMES_TO_STOP = 15
        private const val SILENCE_RMS = 500.0
    }

    /** 所有回调切回主线程，避免非主线程 Toast / Compose state 写入闪退 */
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    private var onResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onState: ((Boolean) -> Unit)? = null

    /** API 方案无需本地模型；保留方法签名兼容 MainActivity 调用 */
    fun isModelReady(): Boolean = true

    fun initModel(onDone: (Boolean) -> Unit) {
        // 无需加载模型，直接回调成功
        mainHandler.post { onDone(true) }
    }

    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onState: (Boolean) -> Unit = {},
        onPartial: (String) -> Unit = {}
    ) {
        if (running.get()) return
        this.onResult = onResult
        this.onError = onError
        this.onState = onState
        running.set(true)
        mainHandler.post { onState(true) }
        worker = Thread({ recordAndTranscribe() }, "asr-record").apply { start() }
    }

    /** 用户主动结束：停止录音，进入转写 */
    fun stopListening() {
        running.set(false)
        runCatching { audioRecord?.stop() }
        worker?.interrupt()
    }

    fun cancelListening() {
        running.set(false)
        onResult = null
        onError = null
        onState = null
        runCatching { audioRecord?.stop() }
        worker?.interrupt()
    }

    fun destroy() {
        cancelListening()
    }

    // ==================== 录音 + 转写 ====================

    private fun recordAndTranscribe() {
        var recorder: AudioRecord? = null
        val pcm = ByteArrayOutputStream()
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val source = pickAudioSource()
            val rec: AudioRecord = try {
                AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, FRAME_BYTES * 2))
            } catch (e: Exception) {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 2, FRAME_BYTES * 2))
            }
            recorder = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IOException("麦克风初始化失败")
            audioRecord = rec
            rec.startRecording()

            val buffer = ByteArray(FRAME_BYTES)
            var silenceFrames = 0
            var gotSpeech = false
            val startAt = System.currentTimeMillis()

            while (running.get() && !Thread.currentThread().isInterrupted) {
                if (System.currentTimeMillis() - startAt > MAX_MS) break
                val read = rec.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                val n = read and 0x7FFFFFFE  // PCM16 必须偶数
                pcm.write(buffer, 0, n)
                if (pcm.size() >= MAX_BYTES) break

                // 静音检测：说话后连续静音约 1.5s 自动结束
                val rms = rms(buffer, n)
                if (rms > SILENCE_RMS) {
                    silenceFrames = 0
                    gotSpeech = true
                } else if (gotSpeech) {
                    silenceFrames++
                    if (silenceFrames >= SILENCE_FRAMES_TO_STOP) break
                }
            }

            val pcmBytes = pcm.toByteArray()
            if (pcmBytes.isEmpty()) {
                mainHandler.post { onError?.invoke("没有识别到内容") }
                return
            }

            // 编码 WAV 并调官方 ASR API
            val wav = encodeWav(pcmBytes)
            val text = uploadAndTranscribe(wav)
            if (text.isNotEmpty()) {
                mainHandler.post { onResult?.invoke(text) }
            } else {
                mainHandler.post { onError?.invoke("没有识别到内容") }
            }
        } catch (e: Exception) {
            if (!Thread.currentThread().isInterrupted) {
                Log.e(TAG, "recordAndTranscribe", e)
                val msg = e.message ?: "语音识别失败"
                mainHandler.post { onError?.invoke(msg) }
            }
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            audioRecord = null
            running.set(false)
            mainHandler.post { onState?.invoke(false) }
        }
    }

    /** 同步调用 ASR API（在 IO 工作线程内），返回识别文字 */
    private fun uploadAndTranscribe(wav: ByteArray): String {
        val tmp = File.createTempFile("asr_", ".wav", context.cacheDir)
        try {
            tmp.writeBytes(wav)
            val part = MultipartBody.Part.createFormData(
                "file", "recording.wav", tmp.asRequestBody("audio/wav".toMediaType())
            )
            val response = NetworkModule.api.transcribe(part).execute()
            if (!response.isSuccessful) {
                throw IOException("语音识别失败 (HTTP ${response.code()})")
            }
            return response.body()?.text?.trim().orEmpty()
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** 16kHz/mono/16bit PCM 编码为 WAV（与官方 recorder.ts 的 encodeWav 一致） */
    private fun encodeWav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)          // fmt chunk size
        header.putShort(1)         // PCM
        header.putShort(1)         // mono
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)  // byte rate
        header.putShort(2)         // block align
        header.putShort(16)        // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }

    private fun pickAudioSource(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
            MediaRecorder.AudioSource.UNPROCESSED
        else MediaRecorder.AudioSource.VOICE_RECOGNITION

    private fun rms(buffer: ByteArray, len: Int): Double {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i < len - 1) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += (sample * sample).toDouble()
            count++
            i += 2
        }
        if (count == 0) return 0.0
        return kotlin.math.sqrt(sum / count)
    }
}
