package io.qwenpaw.node.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** 一次录音要产出什么。 */
sealed interface VoiceTarget {
    /**
     * 端上识别。每 0.1 秒一块喂给 [engine]，结果实时回调；
     * 收尾时把整段文字交给调用方。
     */
    data class Local(
        val engine: LocalEngine,
        /** 识别到停顿即结束，只在「点一下」模式且开了自动停止时有意义。 */
        val autoStop: Boolean,
        val onPartial: (String) -> Unit,
        val onFinal: (String) -> Unit,
    ) : VoiceTarget

    /**
     * 整段上传服务端转写。录音期间只写文件，结束时把补好头的 WAV 交出去。
     */
    data class Upload(val onRecorded: (File, Boolean) -> Unit) : VoiceTarget
}

/**
 * 录音。
 *
 * 两种来源共用同一套采集、重采样与时长上限，区别只在循环体里做什么：
 * 端上识别边录边喂引擎，服务端转写只落盘、结束时补 WAV 头。
 * 合并成一个类是因为采集参数、异常处理与「不能直接取消协程」的收尾约束完全相同，
 * 拆成两份必然漂移。
 */
class VoiceInput(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var record: AudioRecord? = null
    private var stopping = false

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** 录音开始的时刻，界面用它显示计时；null 表示未在录音。 */
    private val _startedAt = MutableStateFlow<Long?>(null)
    val startedAt: StateFlow<Long?> = _startedAt.asStateFlow()

    /** 端上识别的实时文字。服务端转写没有增量结果，此时恒为空串。 */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    /** 取消标志：置位后本次录音的结果直接丢弃。 */
    @Volatile
    private var cancelled = false

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    /** 上传模式的临时目录。上传结束或取消后立刻删除，不留成品。 */
    private fun voiceDir(): File = File(context.cacheDir, "voice").apply { mkdirs() }

    fun cancel() {
        cancelled = true
        stop()
    }

    /**
     * 开始录音。
     *
     * [onError] 用于权限、设备打不开一类的失败。
     */
    fun start(target: VoiceTarget, onError: (String) -> Unit) {
        if (!hasPermission()) {
            _state.value = VoiceState.Error("没有麦克风权限")
            return
        }
        stop()
        stopping = false
        cancelled = false
        _partial.value = ""

        // 端上识别没有文件产物，只有上传模式需要落盘。
        val file = if (target is VoiceTarget.Upload) {
            File(voiceDir(), "voice-${System.currentTimeMillis()}.wav")
        } else {
            null
        }

        job = scope.launch {
            val recorder = try {
                openRecorder()
            } catch (e: Exception) {
                target.releaseEngine()
                _state.value = VoiceState.Error("无法打开麦克风")
                return@launch
            }
            record = recorder
            _state.value = VoiceState.Recording
            _startedAt.value = System.currentTimeMillis()
            var reachedLimit = false
            try {
                recorder.startRecording()
                reachedLimit = when (target) {
                    is VoiceTarget.Local -> pumpLocal(recorder, target)
                    is VoiceTarget.Upload -> {
                        pumpUpload(recorder, file!!)
                        false
                    }
                }
            } catch (e: Exception) {
                onError(e.message ?: "录音中断")
                _state.value = VoiceState.Error(e.message ?: "录音中断")
            } finally {
                runCatching { recorder.release() }
                record = null
                _startedAt.value = null
                when (target) {
                    is VoiceTarget.Local -> finishLocal(target)
                    is VoiceTarget.Upload -> {
                        val limit = !stopping && !cancelled
                        if (file != null) deliverUpload(file, cancelled, target)
                        if (limit) reachedLimit = true
                    }
                }
                // 交出去之后不在这里复位状态：调用方紧接着会把状态置为「正在转写」，
                // 这里先写 Idle 会让浮层闪一下。
                if (_state.value == VoiceState.Recording) _state.value = VoiceState.Idle
                if (reachedLimit) onError("录音已达 ${MAX_DURATION_MS / 1000} 秒上限")
                job = null
            }
        }
    }

    /**
     * 结束录音，成品通过回调交出。
     *
     * 这里只停止录音设备并置停止标志，让采集协程自己收尾。
     * **不能直接取消协程**——取消会跳过 WAV 头回填与引擎收尾，产出不可用的结果。
     */
    fun stop() {
        stopping = true
        record?.let { runCatching { it.stop() } }
    }

    /** 录音已交出、开始上传。由调用方在拿到文件后立刻置位。 */
    fun markTranscribing() {
        _state.value = VoiceState.Transcribing
    }

    /** 回到空闲。上传结束（无论成败）或弹窗关闭后调用。 */
    fun resetToIdle() {
        _state.value = VoiceState.Idle
        _startedAt.value = null
    }

    /**
     * 端上识别循环：每 0.1 秒一块，块长固定。
     *
     * sherpa 按每块采样数反推采样率，块长不固定会报
     * `You changed the input sampling rate` 并直接崩溃。
     *
     * @return 是否因到达时长上限而结束
     */
    private suspend fun pumpLocal(recorder: AudioRecord, target: VoiceTarget.Local): Boolean {
        val resampler = LinearResampler(recorder.sampleRate, SAMPLE_RATE)
        val sameRate = recorder.sampleRate == SAMPLE_RATE
        val buffer = ShortArray(recorder.bufferSizeInFrames.coerceAtLeast(CHUNK * 2))
        val pending = ArrayList<Short>(CHUNK * 4)
        val deadline = System.currentTimeMillis() + MAX_DURATION_MS
        while (currentCoroutineContext().isActive && !stopping) {
            if (System.currentTimeMillis() > deadline) return true
            val read = recorder.read(buffer, 0, buffer.size)
            if (read <= 0) {
                // 停止录音后 read 返回负值，退出循环进入收尾。
                if (read < 0) break
                continue
            }
            val samples = if (sameRate) buffer.copyOf(read) else resampler.process(buffer, read)
            for (sample in samples) pending.add(sample)
            while (pending.size >= CHUNK) {
                val block = ShortArray(CHUNK)
                for (i in 0 until CHUNK) block[i] = pending[i]
                pending.subList(0, CHUNK).clear()
                val text = target.engine.accept(block, block.size)
                if (text.isNotEmpty()) {
                    _partial.value = text
                    target.onPartial(text)
                }
                // 「说完自动停止」：识别到停顿就收尾，把整段结果交出去。
                if (target.autoStop && target.engine.consumeEndpoint()) {
                    stopping = true
                    return false
                }
            }
        }
        return false
    }

    /** 上传模式循环：只落盘裸 PCM，收尾时补头。 */
    private suspend fun pumpUpload(recorder: AudioRecord, target: File) {
        val buffer = ShortArray(recorder.bufferSizeInFrames.coerceAtLeast(SAMPLE_RATE / 4))
        val resampler = LinearResampler(recorder.sampleRate, SAMPLE_RATE)
        val sameRate = recorder.sampleRate == SAMPLE_RATE
        val deadline = System.currentTimeMillis() + MAX_DURATION_MS
        target.outputStream().buffered().use { output ->
            while (currentCoroutineContext().isActive && !stopping) {
                if (System.currentTimeMillis() > deadline) break
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read < 0) break
                    continue
                }
                val samples = if (sameRate) buffer.copyOf(read) else resampler.process(buffer, read)
                val bytes = ByteArray(samples.size * 2)
                for (i in samples.indices) {
                    val s = samples[i].toInt()
                    bytes[i * 2] = (s and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                output.write(bytes)
            }
        }
    }

    /** 端上识别收尾：取最后一段结果，释放引擎。 */
    private fun finishLocal(target: VoiceTarget.Local) {
        val tail = runCatching { target.engine.finish() }.getOrDefault("")
        target.engine.close()
        val text = tail.ifBlank { _partial.value }
        if (!cancelled && text.isNotBlank()) target.onFinal(text)
    }

    /** 上传模式收尾：补 WAV 头后交出文件；取消时直接删掉。 */
    private fun deliverUpload(file: File, cancelled: Boolean, target: VoiceTarget.Upload) {
        runCatching {
            if (file.length() > 0) {
                WavWriter.writeHeader(file, SAMPLE_RATE)
            } else {
                file.delete()
            }
        }
        if (cancelled || file.length() <= 0) {
            file.delete()
            return
        }
        target.onRecorded(file, false)
    }

    private fun openRecorder(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE / 4)
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        ).also {
            if (it.state != AudioRecord.STATE_INITIALIZED) {
                it.release()
                throw IllegalStateException("麦克风不可用")
            }
        }
    }

    private fun VoiceTarget.releaseEngine() {
        if (this is VoiceTarget.Local) runCatching { engine.close() }
    }

    companion object {
        /** 采集与上传统一使用 16kHz。 */
        const val SAMPLE_RATE = 16_000

        /** 端上识别的块长固定为 0.1 秒。 */
        private const val CHUNK = SAMPLE_RATE / 10

        /** 单次录音最长 90 秒。WAV 未压缩，这个上限对应约 2.9MB。 */
        const val MAX_DURATION_MS = 90_000L
    }
}