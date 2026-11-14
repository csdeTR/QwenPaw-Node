package io.qwenpaw.node.voice

import android.content.Context

/**
 * 按变体装配端上识别能力（`_no-sherpa` 变体）。
 *
 * 这个包不含 sherpa 的类与 native 库，语音只能走服务端转写。
 * 返回 [LocalAsr.Disabled] 后，设置页不显示「转写来源」与模型行，
 * 录音走 `VoiceInput` 的整段上传路径。
 */
object LocalAsrProvider {
    fun create(context: Context): LocalAsr = LocalAsr.Disabled
}