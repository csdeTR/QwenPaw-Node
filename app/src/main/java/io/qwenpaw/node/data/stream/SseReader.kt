package io.qwenpaw.node.data.stream

import java.io.BufferedReader

/**
 * 极简 SSE 帧读取器。
 *
 * QwenPaw 的 `/api/console/chat` 每帧只有一条 `data: <json>` 加一个空行，
 * 不发送 `event:` / `id:` / 注释心跳，因此这里只按空行切帧并收集 data 行。
 * 同时兼容 `\r\n` 换行，防止中间代理改写换行符。
 */
class SseReader(private val reader: BufferedReader) {

    /** 读取下一帧，返回 data 拼接结果；流结束时返回 null。 */
    fun nextFrame(): String? {
        val buffer = StringBuilder()
        var sawAnyLine = false
        while (true) {
            val line = reader.readLine() ?: return if (buffer.isNotEmpty()) {
                buffer.toString()
            } else {
                null
            }
            sawAnyLine = true
            if (line.isEmpty()) {
                if (buffer.isEmpty()) continue
                return buffer.toString()
            }
            if (line.startsWith("data:")) {
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(line.removePrefix("data:").removePrefix(" "))
            }
            // 其他字段（event:/id:/retry:）QwenPaw 不使用，直接忽略。
        }
    }
}
