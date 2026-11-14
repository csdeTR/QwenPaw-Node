package io.qwenpaw.node.voice

import kotlinx.coroutines.flow.StateFlow

/** 端上识别模型的下载与安装状态。 */
sealed interface LocalModelState {
    data object NotInstalled : LocalModelState
    data class Downloading(val progress: Float) : LocalModelState
    data object Installing : LocalModelState
    data object Ready : LocalModelState
    data class Failed(val message: String) : LocalModelState
}

/**
 * 一次本地识别会话。
 *
 * 由 [LocalAsr.createEngine] 产出，`VoiceInput` 的采集循环把每 0.1 秒一块的
 * 16kHz 采样喂进来，取回实时文字。
 */
interface LocalEngine {

    /** 送入一块采样，返回到目前为止的完整文字。 */
    fun accept(samples: ShortArray, count: Int): String

    /** 自上次 [accept] 以来是否检测到一次语音结束。只读一次即清零。 */
    fun consumeEndpoint(): Boolean

    /** 结束识别，返回最后一段结果。 */
    fun finish(): String

    fun close()
}

/**
 * 端上识别能力。
 *
 * 两个构建变体各注入一份：`full` 是真正的 sherpa-onnx 引擎，
 * `_no-sherpa` 用 [Disabled]，`available` 为 false。
 *
 * 把它抽成接口而不是在调用处判断 `BuildConfig`，是为了让 `_no-sherpa` 包
 * **根本不包含** sherpa 的类与 native 库——依赖由变体控制，
 * 只要还有一条代码路径引用 `com.k2fsa.sherpa.*`，那份 `.so` 就会被打进包。
 */
interface LocalAsr {

    /** 本变体是否带端上引擎。false 时界面不提供「本地识别」这个选项。 */
    val available: Boolean

    /** 模型状态。不可用时固定为 [LocalModelState.NotInstalled]，界面不展示。 */
    val modelState: StateFlow<LocalModelState>

    /** 下载并解压识别模型。仅 [available] 为 true 时会被调用。 */
    suspend fun download()

    /** 删除已下载的模型。 */
    fun remove()

    /**
     * 创建一次识别会话。
     *
     * [endpointing] 为 true 时开启断句检测（「说完自动停止」）。
     * 模型不完整或本变体没有引擎时返回 null。
     */
    fun createEngine(endpointing: Boolean): LocalEngine?

    /** 没有端上引擎时的占位实现，供 `_no-sherpa` 与国际版以外的场景使用。 */
    object Disabled : LocalAsr {
        override val available: Boolean = false
        override val modelState: StateFlow<LocalModelState> =
            kotlinx.coroutines.flow.MutableStateFlow(LocalModelState.NotInstalled)

        override suspend fun download() = Unit

        override fun remove() = Unit

        override fun createEngine(endpointing: Boolean): LocalEngine? = null
    }
}