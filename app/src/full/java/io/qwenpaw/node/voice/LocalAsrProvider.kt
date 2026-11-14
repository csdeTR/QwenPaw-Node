package io.qwenpaw.node.voice

import android.content.Context

/**
 * 按变体装配端上识别能力。
 *
 * `full` 变体返回真正的 sherpa 实现；`_no-sherpa` 变体在
 * `src/nosherpa/java/` 下有一份同名文件，返回 [LocalAsr.Disabled]。
 *
 * 之所以用同名的变体源码而不是在调用处判断 `BuildConfig`：判断写法仍然会
 * 让编译器看到 sherpa 的类，它的 native 库就会被并入 `_no-sherpa` 包。
 */
object LocalAsrProvider {
    fun create(context: Context): LocalAsr = SherpaAsr(context)
}