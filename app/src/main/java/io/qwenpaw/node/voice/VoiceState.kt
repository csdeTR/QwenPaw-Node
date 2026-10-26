package io.qwenpaw.node.voice

/** 语音识别的界面状态。 */
sealed interface VoiceState {
    data object Idle : VoiceState
    data object Listening : VoiceState
    data class Error(val message: String) : VoiceState
}
