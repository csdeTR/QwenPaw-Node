package io.qwenpaw.node.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.stream.DeliveredFile

/**
 * 智能体通过 `send_file_to_user` 交付的文件。
 *
 * 与官方 Console 的「已发送」卡片对齐：一张卡片一个文件，左侧图片直接给缩略图，
 * 其余文件给文档图标；右侧是文件名、服务端路径与「已发送」标记。
 *
 * 这些卡片**不进**「处理完成 · N 步」那一组：它们是用户要拿去用的结果，
 * 不该因为过程折叠而看不见。
 */
@Composable
fun DeliveredFilesCard(
    files: List<DeliveredFile>,
    imageLoader: ChatImageLoader?,
    connection: Connection?,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        files.forEach { file ->
            DeliveredFileRow(
                file = file,
                imageLoader = imageLoader,
                connection = connection,
                onImageClick = onImageClick,
            )
        }
    }
}

@Composable
private fun DeliveredFileRow(
    file: DeliveredFile,
    imageLoader: ChatImageLoader?,
    connection: Connection?,
    onImageClick: (String) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(
                // 图片可以点开全屏看；非图片没有可展示的内容，不做成可点。
                if (file.isImage) {
                    Modifier.clickable { file.dataUri?.let(onImageClick) }
                } else {
                    Modifier
                },
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeliveredFileThumbnail(file = file, imageLoader = imageLoader, connection = connection)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = file.path,
                style = MaterialTheme.typography.labelSmall,
                color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "已发送",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

/**
 * 左侧缩略图。
 *
 * 图片按 44dp 裁成圆角方块，非图片给一个文档图标。
 * 缩略图交给 [ChatImageLoader] 解码：交付文件的字节是内联的 data URI，
 * 由它统一采样到 1080 宽再画，不会因为原图过大而占满内存。
 */
@Composable
private fun DeliveredFileThumbnail(
    file: DeliveredFile,
    imageLoader: ChatImageLoader?,
    connection: Connection?,
) {
    val shape = RoundedCornerShape(8.dp)
    var bitmap by remember(file.dataUri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.dataUri, imageLoader, connection) {
        val uri = file.dataUri ?: return@LaunchedEffect
        if (imageLoader == null || connection == null) return@LaunchedEffect
        bitmap = imageLoader.load(connection, uri)
    }
    val loaded = bitmap
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(shape),
            )
        } else {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}