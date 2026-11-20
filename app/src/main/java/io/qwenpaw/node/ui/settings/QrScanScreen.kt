package io.qwenpaw.node.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.qwenpaw.node.R
import io.qwenpaw.node.data.qr.PairParse
import io.qwenpaw.node.data.qr.PairQr
import io.qwenpaw.node.data.qr.QrDecoder
import io.qwenpaw.node.data.qr.TokenPair
import java.util.concurrent.Executors

/**
 * 扫码页：相机取帧 + 相册导入。
 *
 * 两条入口共用 [QrDecoder]，因此解码失败与「不是配对码」在两条路上表现一致。
 * 没有摄像头或没有权限时不隐瞒：直接说明并保留相册入口——redroid 与部分
 * 模拟器正是这种情况，而验收用的是事先导出的二维码图片。
 *
 * 这个页面由 [MainActivity] 作为覆盖层拥有，不进入导航栈：扫完或取消都只是
 * 关掉覆盖层，用户回到原来的表单，输入内容不会丢。
 */
@Composable
fun QrScanScreen(
    onBack: () -> Unit,
    onDecoded: (TokenPair) -> Unit,
) {
    val context = LocalContext.current
    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var message by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }

    fun accept(text: String?) {
        if (handled) return
        if (text.isNullOrBlank()) {
            message = context.getString(R.string.qr_scan_empty)
            return
        }
        when (val parsed = PairQr.parse(text)) {
            is PairParse.Ok -> {
                handled = true
                onDecoded(parsed.pair)
            }

            PairParse.NotPairQr -> message = context.getString(R.string.qr_scan_invalid)
            is PairParse.Unsupported -> message = context.getString(R.string.qr_scan_unsupported)
            is PairParse.Broken -> message = context.getString(parsed.reasonRes)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        if (!ok) message = context.getString(R.string.qr_scan_no_permission)
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri).use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                QrDecoder.decodeArgb(pixels, bitmap.width, bitmap.height)
            }
        }.getOrNull()
        accept(text)
    }

    LaunchedEffect(hasCamera) {
        if (!hasCamera) {
            message = context.getString(R.string.qr_scan_no_camera)
        } else if (!granted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (hasCamera && granted) {
            CameraPreview(onText = ::accept)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.qr_scan_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.qr_scan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScanButton(stringResource(R.string.qr_scan_import)) {
                    pickLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
                Spacer(Modifier.width(12.dp))
                ScanButton(stringResource(android.R.string.cancel), onBack)
            }
            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
private fun ScanButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/**
 * 相机预览与逐帧分析。
 *
 * 分析器只取 Y 平面：二维码识别不需要色度，少一次颜色转换就能多解几帧。
 * `KEEP_ONLY_LATEST` 保证回调不被积压——截图大小的帧积压两帧之后，
 * 用户看到的就是几秒前的画面。
 */
@Composable
private fun CameraPreview(onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val cameraProvider = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = cameraProvider
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, YuvAnalyzer(onText)) }
            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

private class YuvAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private var lastAttempt = 0L

    override fun analyze(image: ImageProxy) {
        try {
            // 每帧都解会白耗电：每 6 帧试一次，扫码观感上仍是即时的。
            val now = System.currentTimeMillis()
            if (now - lastAttempt < 100) return
            lastAttempt = now
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            // pixelStride 通常为 1；大于 1 时把有效像素压成连续数组，
            // 否则 PlanarYUVLuminanceSource 会把填充字节也当成像素。
            val width = image.width
            val height = image.height
            val bytes = if (pixelStride == 1) {
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } else {
                ByteArray(width * height).also { out ->
                    var index = 0
                    for (row in 0 until height) {
                        val rowStart = row * rowStride
                        for (col in 0 until width) {
                            out[index++] = buffer.get(rowStart + col * pixelStride)
                        }
                    }
                }
            }
            val stride = if (pixelStride == 1) rowStride else width
            QrDecoder.decodeYuv(bytes, width, height, stride)?.let(onText)
        } finally {
            image.close()
        }
    }
}