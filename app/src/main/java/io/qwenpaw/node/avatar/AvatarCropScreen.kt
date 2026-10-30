package io.qwenpaw.node.avatar

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 头像裁剪页。
 *
 * 取景框是居中的正方形；用户可以双指缩放、单指拖动，图片始终铺满取景框。
 * 点「保存」时按取景框区域裁出正方形，缩到 500×500 后交给 `onSave`。
 *
 * 所有几何换算交给 [CropGeometry]，这一层只维护手势状态与绘制，
 * 好让「拖到底」「放到最小」这类边界能在 JVM 单测里验证。
 */
@Composable
fun AvatarCropScreen(
    bitmap: Bitmap,
    onCancel: () -> Unit,
    onSave: (Bitmap) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    // 手势状态：offset 是相对「铺满居中」位置的位移，单位与画布一致（像素）。
    var userScale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var saving by remember { mutableStateOf(false) }
    // 取景框边长，由布局测量得到；几何计算与绘制共用同一个像素值。
    var framePx by remember { mutableFloatStateOf(0f) }

    fun save() {
        if (saving || framePx <= 0f) return
        saving = true
        val rect = CropGeometry.cropRect(
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            viewSize = framePx,
            userScale = userScale,
            offsetX = offset.x,
            offsetY = offset.y,
        )
        scope.launch {
            val cropped = withContext(Dispatchers.Default) {
                runCatching {
                    Bitmap.createBitmap(bitmap, rect[0], rect[1], rect[2], rect[2])
                }.getOrNull()?.let { square ->
                    Bitmap.createScaledBitmap(
                        square,
                        AvatarStore.OUTPUT_SIZE,
                        AvatarStore.OUTPUT_SIZE,
                        true,
                    )
                }
            }
            saving = false
            if (cropped != null) onSave(cropped)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CROP_BACKGROUND)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "取消",
                tint = Color.White,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onCancel)
                    .padding(8.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "调整头像",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(enabled = !saving) { save() }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                } else {
                    Text(
                        text = "保存",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 取景框取可用宽高中的较小者。边到边页面上这比固定 dp 稳。
            val frame = minOf(maxWidth.value, maxHeight.value)

            Box(
                modifier = Modifier
                    .size(frame.dp)
                    // 必须裁剪到取景框内。`drawImage` 的 dstSize 是「图片尺寸 × 有效缩放」，
                    // 放大或长宽比极端时会大于取景框，而 Compose 的绘制默认不按布局边界裁剪，
                    // 溢出的部分会直接画到上方标题行之上，把「取消 / 调整头像 / 保存」压住。
                    .clipToBounds()
                    .onSizeChanged { framePx = it.width.toFloat() }
                    .pointerInput(bitmap) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            userScale = (userScale * zoom).coerceIn(1f, MAX_ZOOM)
                            val clamped = CropGeometry.clampOffset(
                                imageWidth = bitmap.width,
                                imageHeight = bitmap.height,
                                viewSize = size.width.toFloat(),
                                userScale = userScale,
                                offsetX = offset.x + pan.x,
                                offsetY = offset.y + pan.y,
                            )
                            offset = Offset(clamped.first, clamped.second)
                        }
                    },
            ) {
                // 用 Canvas 自己算 dst 位置，而不是给 Image 加 graphicsLayer：
                // 缩放与位移要和 CropGeometry 用的是同一套公式，否则裁剪结果会偏。
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val scale = CropGeometry.effectiveScale(
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                        viewSize = size.width,
                        userScale = userScale,
                    )
                    val dstW = bitmap.width * scale
                    val dstH = bitmap.height * scale
                    val left = (size.width - dstW) / 2f + offset.x
                    val top = (size.height - dstH) / 2f + offset.y
                    drawImage(
                        image = image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bitmap.width, bitmap.height),
                        dstOffset = IntOffset(left.toInt(), top.toInt()),
                        dstSize = IntSize(dstW.toInt(), dstH.toInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
                // 取景框描边与四角短线：白色描边标出裁剪边界，四角短线方便对齐。
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.85f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    val len = size.width * 0.1f
                    val cornerWidth = 3.dp.toPx()
                    listOf(
                        Offset(0f, 0f),
                        Offset(size.width, 0f),
                        Offset(0f, size.height),
                        Offset(size.width, size.height),
                    ).forEach { point ->
                        val dx = if (point.x == 0f) len else -len
                        val dy = if (point.y == 0f) len else -len
                        drawLine(Color.White, point, Offset(point.x + dx, point.y), cornerWidth)
                        drawLine(Color.White, point, Offset(point.x, point.y + dy), cornerWidth)
                    }
                }
            }
        }
    }
}

/** 裁剪页底色用近黑，避免浅色图片与页面背景混淆边界。 */
private val CROP_BACKGROUND = Color(0xFF101010)

/** 最大放大倍数。再大只是把像素点放大成色块，对头像没有意义。 */
private const val MAX_ZOOM = 4f