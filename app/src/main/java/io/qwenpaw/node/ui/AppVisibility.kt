package io.qwenpaw.node.ui

/**
 * 记录当前可见的 Activity 数量。
 *
 * ViewModel 拿不到 Activity 生命周期，用这个轻量计数器判断"App 是否在前台"，
 * 从而决定回复结束时要不要发本地通知。
 */
object AppVisibility {
    @Volatile
    var visibleCount: Int = 0
        private set

    fun onStart() {
        visibleCount++
    }

    fun onStop() {
        visibleCount = (visibleCount - 1).coerceAtLeast(0)
    }
}
