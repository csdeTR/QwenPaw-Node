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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 本地语音输入。
 *
 * 录音在独立协程里跑，识别结果通过 [partial] 实时抛给界面。
 * 识别引擎由 sherpa-onnx 提供，模型放在应用私有目录。
 */
class VoiceInput(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var record: AudioRecord? = null
    private var stopping = false

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    /** 取消标志：置位后本次录音的结果直接丢弃。 */
    @Volatile
    private var cancelled = false

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    /** 模型目录是否已就绪。 */
    fun modelDir(): File? = SpeechModelManager(context).installedDir()
        .takeIf { it.isDirectory && it.listFiles().orEmpty().isNotEmpty() }

    fun start(
        model: File,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        /** 识别到说话停顿后自动结束，只在「点一下」模式有意义。 */
        autoStop: Boolean = false,
    ) {
        if (!hasPermission()) {
            _state.value = VoiceState.Error("没有麦克风权限")
            return
        }
        stop()
        stopping = false
        cancelled = false
        _partial.value = ""
        _state.value = VoiceState.Listening

        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 4)

        job = scope.launch {
            // 开了「说完自动停止」才启用 sherpa 的端点检测。
            val engine = StreamingRecognizer.create(model, sampleRate, endpointing = autoStop)
            if (engine == null) {
                _state.value = VoiceState.Error("模型文件不完整，重新下载一次")
                return@launch
            }
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 2,
                )
            } catch (e: Exception) {
                engine.close()
                _state.value = VoiceState.Error("无法打开麦克风")
                return@launch
            }
            record = recorder
            try {
                recorder.startRecording()
                val actualRate = recorder.sampleRate
                val resampler = LinearResampler(actualRate, sampleRate)
                val buffer = ShortArray(minBuffer)
                // sherpa 按每块时长推断采样率，这里固定成 0.1 秒一块
                val chunk = sampleRate / 10
                val pending = ArrayList<Short>(chunk * 2)
                // 单次录音最长 90 秒，超时自动收尾
                val deadline = System.currentTimeMillis() + MAX_DURATION_MS
                while (isActive && !stopping) {
                    if (System.currentTimeMillis() > deadline) {
                        break
                    }
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) {
                        // 停止录音后 read 会返回负值，退出循环进入收尾
                        if (read < 0) break
                        continue
                    }
                    val samples = if (actualRate == sampleRate) {
                        buffer.copyOf(read)
                    } else {
                        resampler.process(buffer, read)
                    }
                    for (sample in samples) pending.add(sample)
                    while (pending.size >= chunk) {
                        val block = ShortArray(chunk)
                        for (i in 0 until chunk) block[i] = pending[i]
                        pending.subList(0, chunk).clear()
                        val text = engine.accept(block, block.size)
                        if (text.isNotEmpty()) {
                            _partial.value = text
                            onPartial(text)
                        }
                        // 「说完自动停止」：识别到停顿就收尾，把整段结果交出去。
                        if (engine.consumeEndpoint()) {
                            stopping = true
                            break
                        }
                    }
                    if (stopping) break
                }
            } catch (e: Exception) {
                onError(e.message ?: "录音中断")
                _state.value = VoiceState.Error(e.message ?: "录音中断")
            } finally {
                runCatching { recorder.release() }
                record = null
                val tail = runCatching { engine.finish() }.getOrDefault("")
                engine.close()
                val text = tail.ifBlank { _partial.value }
                if (!cancelled && text.isNotBlank()) onFinal(text)
                if (_state.value == VoiceState.Listening) _state.value = VoiceState.Idle
                job = null
            }
        }
    }

    /**
     * 结束录音，最后一段结果会通过 onFinal 回调。
     *
     * 这里只停止录音设备，让采集协程自己收尾，
     * 因为直接取消协程会跳过收尾逻辑，状态也不会复位。
     */
    fun stop() {
        stopping = true
        record?.let { runCatching { it.stop() } }
        if (job == null && _state.value == VoiceState.Listening) {
            _state.value = VoiceState.Idle
        }
    }

    /** 取消录音：结果不填入输入框。 */
    fun cancel() {
        cancelled = true
        stop()
    }

    companion object {
        /** 单次录音最长 90 秒。 */
        const val MAX_DURATION_MS = 90_000L
    }
}
