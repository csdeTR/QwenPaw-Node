package io.qwenpaw.node.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * sherpa-onnx 流式识别引擎的薄封装。
 *
 * 模型目录里需要有 encoder、decoder、joiner 三个 onnx 文件和 tokens.txt，
 * 文件名按官方模型包的结构自动识别。
 */
class StreamingRecognizer private constructor(
    private val recognizer: OnlineRecognizer,
    private val stream: OnlineStream,
    private val sampleRate: Int,
    /** 是否开启了断句检测（「说完自动停止」）。 */
    private val endpointing: Boolean,
) {

    /** 本次 accept 期间是否命中了断句，供 [consumeEndpoint] 读取。 */
    @Volatile
    private var reachedEndpoint = false

    /** 送入一段 16kHz 单声道采样，返回到目前为止的完整文字。 */
    fun accept(samples: ShortArray, count: Int): String {
        if (count <= 0) return recognizer.getResult(stream).text
        val floats = FloatArray(count) { samples[it] / 32768f }
        // acceptWaveform 的第二个参数是采样率，不是采样数
        stream.acceptWaveform(floats, sampleRate)
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        val text = recognizer.getResult(stream).text
        // 开了端点检测时，识别到停顿就记一笔，交给采集循环收尾。
        // 这里不 reset：reset 会把已识别的文字一起清掉，而我们需要保留它。
        if (endpointing && recognizer.isEndpoint(stream)) {
            reachedEndpoint = true
        }
        return text
    }

    /**
     * 自上次 [accept] 以来是否检测到一次语音结束。
     *
     * 只在创建时开了 [endpointing] 才有意义，读一次即清零。
     */
    fun consumeEndpoint(): Boolean {
        val hit = reachedEndpoint
        reachedEndpoint = false
        return hit
    }

    /** 结束录音，返回最后一段结果。 */
    fun finish(): String {
        stream.inputFinished()
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        return recognizer.getResult(stream).text
    }

    /**
     * 结束本次识别。recognizer 是全局缓存的，这里只释放 stream。
     */
    fun close() {
        runCatching { stream.release() }
    }

    companion object {
        /** 在模型目录里找指定后缀的文件，官方包会多一层子目录。 */
        private fun find(dir: File, suffix: String): String? {
            dir.walkTopDown()
                .firstOrNull { it.isFile && it.name.endsWith(suffix) }
                ?.let { return it.absolutePath }
            return null
        }

        /**
         * 已加载的识别器缓存。
         *
         * 模型加载要好几秒，如果每次按住麦克风都重新加载，
         * 按下到真正开始录音会有明显延迟，前几个字直接丢掉。
         * OnlineRecognizer 加载一次后可以反复 createStream 复用。
         */
        @Volatile
        private var cached: Pair<String, OnlineRecognizer>? = null

        /** 模型目录是否完整可用。 */
        fun isReady(dir: File?): Boolean {
            if (dir == null || !dir.isDirectory) return false
            return find(dir, "encoder.int8.onnx") != null || find(dir, "encoder.onnx") != null
        }

        /**
     * 创建识别器。assets 传 null 即可，模型走绝对路径。
     *
     * [endpointing] 为 true 时开启 sherpa 自带的断句检测，识别到一段话结束
     * 会返回 `isEndpoint`；用在「说完自动停止」的语音模式上。
     * 模型不全时返回 null。
     */
    fun create(dir: File, sampleRate: Int, endpointing: Boolean = false): StreamingRecognizer? {
            val encoder = find(dir, "encoder.int8.onnx") ?: find(dir, "encoder.onnx") ?: return null
            val decoder = find(dir, "decoder.int8.onnx") ?: find(dir, "decoder.onnx") ?: return null
            val joiner = find(dir, "joiner.int8.onnx") ?: find(dir, "joiner.onnx") ?: return null
            val tokens = find(dir, "tokens.txt") ?: return null

            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80, dither = 0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder,
                        decoder = decoder,
                        joiner = joiner,
                    ),
                    tokens = tokens,
                    numThreads = 2,
                    debug = false,
                ),
                // 关掉自带断句：默认模式下由用户点「说完了」或 90 秒超时收尾。
                // 「说完自动停止」才临时打开，见 accept 的返回值。
                enableEndpoint = endpointing,
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
            )
            return runCatching {
                // 是否开启端点检测会改变识别器行为，两种配置各缓存一份。
                val key = "$encoder|$decoder|$joiner|$tokens|$sampleRate|$endpointing"
                val recognizer = cached?.takeIf { it.first == key }?.second
                    ?: OnlineRecognizer(assetManager = null, config = config).also {
                        cached = key to it
                    }
                StreamingRecognizer(recognizer, recognizer.createStream(), sampleRate, endpointing)
            }.getOrNull()
        }
    }
}
