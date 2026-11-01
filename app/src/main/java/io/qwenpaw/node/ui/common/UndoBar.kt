package io.qwenpaw.node.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 可撤销操作的控制器。
 *
 * **只用于真正能撤销的动作。** 服务端提供撤销的只有归档
 * （`POST /api/chats/{id}/unarchive`，幂等且立即可用）；
 * 删除对话是 `DELETE` 立即生效，没有回收站，因此删除路径不得调用这里。
 *
 * 倒计时由界面层承担，控制器只负责「当前该显示哪一条」。
 * 这样单元测试可以不依赖时间，直接断言 [state] 的流转。
 */
class UndoController {

    /** 当前待撤销的一条。为 null 表示不显示。 */
    var state by mutableStateOf<UndoOffer?>(null)
        private set

    /**
     * 提供一次撤销机会。
     *
     * 后到的调用会顶掉先到的：同时归档多条时，只有最近那条能撤销。
     * 这是有意的取舍——同时给出多条撤销入口，用户根本来不及点。
     */
    fun offer(message: String, onUndo: () -> Unit) {
        state = UndoOffer(message, onUndo)
    }

    /** 收起提示。[consumed] 为真表示已被撤销或已被用户主动关闭。 */
    fun dismiss() {
        state = null
    }
}

/** 一次可撤销操作的描述。 */
data class UndoOffer(
    val message: String,
    val onUndo: () -> Unit,
)

/**
 * 撤销提示条。
 *
 * 默认展示 [durationMillis] 毫秒后自动收起。倒计时用 `delay` 实现，
 * 界面被销毁时协程随之取消，不会留下野定时器。
 */
@Composable
fun UndoBarHost(
    controller: UndoController,
    modifier: Modifier = Modifier,
    durationMillis: Long = 10_000L,
) {
    val offer = controller.state
    // key 用 message：同一段文案再次触发时重新开始倒计时，
    // 否则连续归档两条同名对话时，第二条会继承第一条的剩余时间。
    LaunchedEffect(offer?.message) {
        if (offer != null) {
            delay(durationMillis)
            controller.dismiss()
        }
    }

    AnimatedVisibility(
        visible = offer != null,
        enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 2 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 2 },
        modifier = modifier,
    ) {
        // 内容取自 controller 而不是 offer：退出动画期间 offer 已变 null，
        // 直接用它会渲染出一张空白条。
        val current = controller.state ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = current.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        val action = current.onUndo
                        // 先收起再执行：撤销本身可能有网络往返，
                        // 让提示条立刻消失比等请求回来更符合「点了就生效」的预期。
                        controller.dismiss()
                        action()
                    },
                ) {
                    Text(
                        text = "撤销",
                        color = MaterialTheme.colorScheme.inversePrimary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}