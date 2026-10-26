package io.qwenpaw.node.voice

import kotlin.math.floor

/**
 * 线性插值重采样。
 *
 * 部分设备的麦克风实际采样率低于请求值，识别模型却要求固定采样率，
 * 这里做一次简单的重采样，跨块的相位用 [position] 和 [tail] 保存。
 */
class LinearResampler(private val fromRate: Int, private val toRate: Int) {

    private val step = fromRate.toDouble() / toRate
    private var position = 0.0
    private var tail: ShortArray = ShortArray(0)

    fun process(input: ShortArray, count: Int): ShortArray {
        if (fromRate == toRate) return input.copyOf(count)
        val data = if (tail.isEmpty()) input.copyOf(count) else tail + input.copyOf(count)
        val outSize = ((data.size - position) / step).toInt().coerceAtLeast(0)
        if (outSize <= 0) {
            tail = data
            return ShortArray(0)
        }
        val out = ShortArray(outSize)
        for (i in 0 until outSize) {
            val index = position + i * step
            val left = floor(index).toInt()
            val frac = index - left
            val a = data[left].toInt()
            val b = if (left + 1 < data.size) data[left + 1].toInt() else a
            out[i] = (a + (b - a) * frac).toInt().toShort()
        }
        val consumed = position + outSize * step
        val keepFrom = floor(consumed).toInt()
        tail = if (keepFrom < data.size) data.copyOfRange(keepFrom, data.size) else ShortArray(0)
        position = consumed - keepFrom
        return out
    }
}
