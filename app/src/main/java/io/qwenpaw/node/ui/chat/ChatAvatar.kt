package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import io.qwenpaw.node.avatar.AvatarStore

/**
 * 圆形头像。
 *
 * 有自定义头像时显示图片，没有时回落成「昵称首字 + 按名字取色的底色」。
 * 回落不是偷懒：用户没有设置头像时，一个空圆圈比首字更难分辨谁是谁。
 *
 * 用 Coil 而不是 `BitmapFactory` 读文件，是因为后者只能解出 GIF 的第一帧。
 * [AvatarStore.imageLoader] 里注册了逐帧解码组件，动图会自己循环播放；
 * 静态 PNG 走同一条路径，没有第二条代码分支。
 *
 * [version] 变化时重新构建请求：设置页换完头像返回聊天页，靠它触发刷新。
 */
@Composable
fun ChatAvatar(
    store: AvatarStore?,
    kind: String,
    name: String,
    size: Dp = 32.dp,
    version: Long = 0,
) {
    val file = remember(kind, version) { runCatching { store?.fileFor(kind) }.getOrNull() }
    // 加载失败（文件损坏、格式不被支持）就退回首字，不能留一个空洞。
    var failed by remember(kind, version) { mutableStateOf(false) }
    var loaded by remember(kind, version) { mutableStateOf(false) }
    val showImage = file != null && !failed
    // 动图必须走 AvatarStore 里注册过逐帧解码器的加载器，否则只会画出第一帧。
    val loader = store?.imageLoader

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (loaded && showImage) Color.Transparent else fallbackColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        // 首字只在图片就绪之前露出来。图片就绪后不再叠在下面：
        // 带头像的 GIF 常有透明区域，若首字还留着会从透明处透出来。
        if (!loaded || !showImage) {
            Text(
                text = initialOf(name),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
        }
        if (showImage && loader != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(file)
                    // 头像总在同一位置反复出现，淡入会让长列表滚动时一直闪。
                    .crossfade(false)
                    .build(),
                contentDescription = name,
                imageLoader = loader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Success -> loaded = true
                        is AsyncImagePainter.State.Error -> {
                            loaded = false
                            failed = true
                        }
                        else -> Unit
                    }
                },
            )
        }
    }
}

/** 取名字的第一个可见字符。空名字用一个人形符号兜住，避免渲染出空圆圈。 */
internal fun initialOf(name: String): String {
    val first = name.trim().firstOrNull { !it.isWhitespace() } ?: return "?"
    return first.uppercase()
}

/**
 * 由名字派生一个稳定的底色。
 *
 * 同一台设备上同一个名字永远得到同一个颜色，用户能靠颜色记住「谁是谁」。
 * 只取色相，饱和度与亮度固定，保证白色首字在两种主题下都够清楚。
 */
internal fun fallbackColor(name: String): Color {
    val palette = listOf(
        0xFF5B8DEF, // 蓝
        0xFF2FA37A, // 绿
        0xFFB4793C, // 棕
        0xFF8A6BD1, // 紫
        0xFFC0555A, // 红
        0xFF3F8F9A, // 青
    )
    val hash = name.fold(0) { acc, c -> acc * 31 + c.code }
    val index = (hash % palette.size + palette.size) % palette.size
    return Color(palette[index])
}