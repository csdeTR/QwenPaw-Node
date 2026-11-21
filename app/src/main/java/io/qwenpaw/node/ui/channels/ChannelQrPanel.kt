package io.qwenpaw.node.ui.channels

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R

/** 二维码授权面板的阶段。 */
enum class ChannelAuthPhase {
    LOADING,
    WAITING,
    SAVING,
    EXPIRED,
    FAILED,
}

/** 二维码授权面板的状态。非空即表示面板打开。 */
data class ChannelAuthState(
    val channel: String,
    val phase: ChannelAuthPhase = ChannelAuthPhase.LOADING,
    /** 不带 `data:` 前缀的 base64 PNG。 */
    val qrBase64: String = "",
    /** 轮询与主动回收都要用它。 */
    val pollToken: String = "",
    /** 服务端最近一次的原始状态词，用于区分「等待扫码」与「已扫码待确认」。 */
    val rawStatus: String = "",
    /** 失败原因或保存失败说明。 */
    val failure: String? = null,
)

/**
 * 二维码面板。
 *
 * 二维码是服务端生成的不带前缀的 base64 PNG，这里直接解码，不走网络。
 * 关闭时 ViewModel 会回收服务端会话。
 */
@Composable
fun ChannelQrPanel(
    auth: ChannelAuthState,
    name: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val bitmap = remember(auth.qrBase64) {
        runCatching {
            val bytes = Base64.decode(auth.qrBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "${stringResource(R.string.channel_qr_title)} · $name",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(16.dp))

            when {
                auth.phase == ChannelAuthPhase.LOADING -> {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.channel_qr_getting),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }

                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(260.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                )

                auth.phase == ChannelAuthPhase.WAITING -> Text(
                    text = stringResource(R.string.channel_qr_load_failed, ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> Unit
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = when (auth.phase) {
                    ChannelAuthPhase.LOADING -> ""
                    ChannelAuthPhase.WAITING -> when (auth.rawStatus) {
                        "scanned" -> stringResource(R.string.channel_qr_scanned)
                        "waiting", "" -> stringResource(R.string.channel_qr_waiting)
                        else -> stringResource(R.string.channel_qr_raw_status, auth.rawStatus)
                    }

                    ChannelAuthPhase.SAVING -> stringResource(R.string.channel_qr_saving)
                    ChannelAuthPhase.EXPIRED -> stringResource(R.string.channel_qr_expired)
                    ChannelAuthPhase.FAILED -> auth.failure
                        ?: stringResource(R.string.channel_qr_failed_unknown)
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (auth.phase == ChannelAuthPhase.EXPIRED || auth.phase == ChannelAuthPhase.FAILED) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.channel_qr_refresh), color = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.channel_qr_close), color = Color.White)
                }
            }
        }
    }
}