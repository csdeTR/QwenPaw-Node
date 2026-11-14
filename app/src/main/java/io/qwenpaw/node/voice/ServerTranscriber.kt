package io.qwenpaw.node.voice

import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.QwenPawApi
import java.io.File
import java.io.IOException

/**
 * 上传一段录音，返回服务端转写的文本。
 *
 * 失败原因按实测的三种形状翻译成中文（实测见 `docs/protocol.md` 语音转写一节）：
 *
 * | 状态码 | detail 形态 | 含义 |
 * | --- | --- | --- |
 * | 400 | 对象 `{code, message}` | `TRANSCRIPTION_DISABLED` 或 `UNSUPPORTED_FILE_TYPE` |
 * | 422 | 数组（FastAPI 校验） | 请求缺 `file` 字段，属客户端缺陷 |
 * | 500 | 字符串 | 服务端 provider 不可用或调用失败 |
 *
 * 服务端在 provider 失败时也会回 500，因此**不要把 500 说成「网络问题」**，
 * 也不要说成「没配置」——两者需要不同的处置，界面上只给一句「服务端转写失败，
 * 请检查服务端的转写配置」。
 */
class ServerTranscriber(private val api: QwenPawApi) {

    /**
     * 转写 [file]。无论成功失败，结束时都会删除这个临时文件。
     *
     * 空文本按失败处理：用户录了一段却没拿到字，多半是 provider 那边的问题，
     * 把空串填进输入框等于什么都没说。
     *
     * @throws TranscriptionException 失败原因已翻译成可直接展示的中文
     */
    fun transcribe(conn: Connection, file: File): String {
        try {
            val text = api.transcribeAudio(conn, file, file.name).trim()
            if (text.isEmpty()) {
                throw TranscriptionException("服务端没有返回识别结果，请说得更清楚一些或检查转写配置")
            }
            return text
        } catch (e: ApiException) {
            throw TranscriptionException(messageFor(e))
        } catch (e: IOException) {
            throw TranscriptionException("无法连接服务器，录音没有上传")
        } finally {
            file.delete()
        }
    }

    private fun messageFor(e: ApiException): String {
        val detail = e.detail.orEmpty()
        return when (e.code) {
            400 -> when {
                detail.contains("TRANSCRIPTION_DISABLED") -> "服务端未启用语音转写"
                detail.contains("UNSUPPORTED_FILE_TYPE") -> "服务端不接受这种录音格式"
                else -> "服务端拒绝了这次转写请求"
            }
            401, 403 -> "登录已失效，请重新连接后再试"
            404 -> "当前服务端版本不支持语音转写"
            422 -> "录音没有正确上传"
            413 -> "录音文件过大，请缩短录音时长"
            500, 502, 503, 504 -> "服务端转写失败，请检查服务端的转写配置"
            else -> "转写失败（HTTP ${e.code}）"
        }
    }
}

/** 转写失败的统一异常，[message] 已是给用户看的中文。 */
class TranscriptionException(override val message: String) : Exception(message)