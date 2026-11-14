package io.qwenpaw.node.voice

/** 语音输入的界面状态。 */
sealed interface VoiceState {
    data object Idle : VoiceState

    /** 正在采集。本地识别会同时显示实时文字，服务端转写只显示计时。 */
    data object Recording : VoiceState

    /** 录音已结束，正在上传并等待服务端返回文本。仅服务端转写会进入此态。 */
    data object Transcribing : VoiceState

    data class Error(val message: String) : VoiceState
}