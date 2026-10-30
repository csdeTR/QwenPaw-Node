package io.qwenpaw.node.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 会话图片加载器。
 *
 * 不引入 Coil，直接 OkHttp + BitmapFactory：
 * - `data:image/...;base64,...`：历史接口把本地图片内联成 data URI，本地解码。
 * - `http(s)://...`：外链，直接下载。
 * - 其余（服务端本地绝对路径）：走 `GET /api/files/preview/{path}`。
 *
 * 结果放进内存 LRU，避免列表滚动时反复解码。
 */
class ChatImageLoader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 图片下载失败往往只表现为「一直转圈」，挂日志才能在排查时看到状态码。
        // 挂在 network 层：图片也可能被反代跳转，跳转那一跳只有这一层看得到。
        .addNetworkInterceptor(io.qwenpaw.node.log.LoggingInterceptor())
        .build()

    private val cache = object : LruCache<String, Bitmap>(24) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /** 把各种形式的图片地址解析成可下载的完整 URL。 */
    fun resolve(conn: Connection, url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("data:") ||
            trimmed.startsWith("http://") ||
            trimmed.startsWith("https://")
        ) {
            return trimmed
        }
        // 服务端本地绝对路径 → 预览端点。
        val path = "/api/files/preview/" + trimmed.removePrefix("/")
        return QwenPawApi.resolve(conn.baseUrl, path).toString()
    }

    suspend fun load(conn: Connection, url: String): Bitmap? {
        val key = resolve(conn, url)
        cache.get(key)?.let { return it }
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    key.startsWith("data:") -> decodeDataUri(key)
                    else -> {
                        val builder = Request.Builder().url(key)
                        conn.token?.takeIf { it.isNotBlank() }
                            ?.let { builder.header("Authorization", "Bearer $it") }
                        builder.header("X-Agent-Id", conn.agentId)
                        client.newCall(builder.build()).execute().use { response ->
                            if (!response.isSuccessful) null
                            else response.body?.bytes()
                        }
                    }
                }
            }.getOrNull()
        }
        val bitmap = bytes?.let { decodeSampled(it) } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun decodeDataUri(uri: String): ByteArray? {
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val payload = uri.substring(comma + 1)
        val cleaned = payload.filterNot { it == '\n' || it == '\r' || it == ' ' }
        return runCatching { Base64.decode(cleaned, Base64.DEFAULT) }.getOrNull()
    }

    /** 先读尺寸再按目标宽度采样，避免超大图直接 OOM。 */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
    }

    companion object {
        private const val TARGET_WIDTH = 1080
    }
}

/** 会话里的图片块：自适应高度，加载失败给文案。 */
@Composable
fun RemoteImage(
    loader: ChatImageLoader?,
    conn: Connection?,
    url: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, loader, conn) {
        if (loader == null || conn == null) return@LaunchedEffect
        val result = loader.load(conn, url)
        if (result == null) failed = true else bitmap = result
    }

    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = bitmap != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "图片",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )

            failed -> Text(
                text = "图片加载失败",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}