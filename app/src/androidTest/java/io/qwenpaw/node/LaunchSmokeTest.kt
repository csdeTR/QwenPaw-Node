package io.qwenpaw.node

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备端冒烟测试：应用能启动、Activity 能到达 RESUMED、Compose 宿主视图真实渲染。
 *
 * 这里刻意不依赖 Compose 语义树——无头模拟器上语义树附着时序不稳定，
 * 会频繁误报；界面内容的完整验收由 `scripts/e2e_acceptance.sh` 的
 * 真实截图流程覆盖。
 */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {

    @Test
    fun activityReachesResumedAndRendersComposeHost() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var resumed = false
            var hasContent = false
            scenario.onActivity { activity ->
                resumed = !activity.isFinishing
                val content = activity.window?.decorView?.findViewById<android.view.ViewGroup>(
                    android.R.id.content,
                )
                hasContent = (content?.childCount ?: 0) > 0
            }
            assert(resumed) { "Activity 未能正常启动" }
            assert(hasContent) { "Activity 未渲染任何内容视图" }
        }
    }
}
