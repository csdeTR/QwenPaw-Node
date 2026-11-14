package io.qwenpaw.node.voice

import java.io.File
import java.io.RandomAccessFile

/**
 * 把 16 位单声道 PCM 写成 WAV。
 *
 * 录音阶段先只写裸 PCM 数据，结束时回来补 44 字节头：边录边写头需要在开头
 * 预留位置并在收尾时回填长度，而 `RandomAccessFile` 一次就能做完，比在内存里
 * 攒满 90 秒（约 2.9MB）再一次性写盘更省内存。
 *
 * 之所以把这件事单独抽出来，是因为它是这条链路里唯一能脱离设备做单测的部分：
 * 头字段算错时本地播不出来，而服务端仍可能照常接受上传。
 */
object WavWriter {

    /** WAV 头的固定长度。`fmt ` 块取 16 字节，因此没有 `fact` / `LIST` 等可选块。 */
    const val HEADER_SIZE = 44

    /** 采样位宽。整条链路固定 16 位，`AudioRecord` 也按这个格式配置。 */
    private const val BITS_PER_SAMPLE = 16

    /** 单声道。 */
    private const val CHANNELS = 1

    /**
     * 在 [file] 开头写入 WAV 头，并把已有的裸 PCM 数据后移。
     *
     * [sampleRate] 是数据实际被采集到的采样率（重采样之后的值），不是设备报告的采样率。
     * 调用方应当先写好 PCM 再调它；重复调用会写出两个头。
     */
    fun writeHeader(file: File, sampleRate: Int) {
        val dataSize = file.length()
        val payload = ByteArray(dataSize.toInt())
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.readFully(payload)
            raf.seek(0)
            raf.write(header(sampleRate, dataSize.toInt()))
            raf.write(payload)
        }
    }

    /**
     * 构造 44 字节 WAV 头。
     *
     * 所有多字节字段是小端序。`RIFF` 的长度字段是「文件总长减 8」，
     * `data` 的长度字段是纯数据字节数——这两个值写错时播放器会截断或读到噪音，
     * 而服务端不做校验。
     */
    fun header(sampleRate: Int, dataSize: Int): ByteArray {
        val byteRate = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val out = ByteArray(HEADER_SIZE)
        var i = 0

        fun ascii(text: String) {
            for (c in text) out[i++] = c.code.toByte()
        }

        fun le32(value: Int) {
            out[i++] = (value and 0xFF).toByte()
            out[i++] = ((value shr 8) and 0xFF).toByte()
            out[i++] = ((value shr 16) and 0xFF).toByte()
            out[i++] = ((value shr 24) and 0xFF).toByte()
        }

        fun le16(value: Int) {
            out[i++] = (value and 0xFF).toByte()
            out[i++] = ((value shr 8) and 0xFF).toByte()
        }

        ascii("RIFF")
        le32(HEADER_SIZE - 8 + dataSize)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)
        le16(1) // PCM，非压缩
        le16(CHANNELS)
        le32(sampleRate)
        le32(byteRate)
        le16(blockAlign)
        le16(BITS_PER_SAMPLE)
        ascii("data")
        le32(dataSize)
        return out
    }
}