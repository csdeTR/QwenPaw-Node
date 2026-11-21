package io.qwenpaw.node.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import io.qwenpaw.node.R
import io.qwenpaw.node.data.qr.PairParse
import io.qwenpaw.node.data.qr.PairQr
import io.qwenpaw.node.data.qr.TokenPair
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.compose.runtime.rememberCoroutineScope

/**
 * 扫码页：ML Kit 离线识别加 CameraX 取帧。
 *
 * 早期版本把 CameraX 的 Y 平面直接交给 ZXing，弱光、倾斜与行填充都会明显
 * 降低识别率。这里改用与 OpenClaw 安卓端相同的做法：ML Kit 只识别 QR，
 * 帧带旋转角，后摄优先；另补手电筒、双指变焦、点击对焦与相册降采样。
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
    val scope = rememberCoroutineScope()
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
    var camera by remember { mutableStateOf<Camera?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }

    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    DisposableEffect(scanner) {
        onDispose { scanner.close() }
    }

    val currentOnDecoded by rememberUpdatedState(onDecoded)

    /** 返回 true 表示已接手结果，相机可以停止继续识别。 */
    fun accept(text: String?): Boolean {
        if (handled) return true
        if (text.isNullOrBlank()) {
            message = context.getString(R.string.qr_scan_empty)
            return false
        }
        return when (val parsed = PairQr.parse(text)) {
            is PairParse.Ok -> {
                handled = true
                currentOnDecoded(parsed.pair)
                true
            }

            PairParse.NotPairQr -> {
                message = context.getString(R.string.qr_scan_invalid)
                false
            }

            is PairParse.Unsupported -> {
                message = context.getString(R.string.qr_scan_unsupported)
                false
            }

            is PairParse.Broken -> {
                message = context.getString(parsed.reasonRes)
                false
            }
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
        scope.launch(Dispatchers.IO) {
            val bitmap = runCatching { decodeGalleryBitmap(context, uri) }.getOrNull()
            if (bitmap == null) {
                scope.launch {
                    message = context.getString(R.string.qr_scan_image_failed)
                }
                return@launch
            }
            val text = runCatching { scanner.scan(bitmap) }.getOrNull()
            bitmap.recycle()
            scope.launch {
                accept(text)
            }
        }
    }

    LaunchedEffect(hasCamera) {
        if (!hasCamera) {
            message = context.getString(R.string.qr_scan_no_camera)
        } else if (!granted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(camera, torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (hasCamera && granted) {
            CameraPreview(
                scanner = scanner,
                onText = ::accept,
                onCamera = { camera = it },
                onFlash = { available ->
                    hasFlash = available
                    if (!available) torchOn = false
                },
                onCameraError = {
                    message = context.getString(R.string.qr_scan_camera_failed)
                },
            )
            // 取景框只做视觉引导，不做裁剪：ML Kit 自己找码，
            // 手动裁剪反而会把贴在框边的码切掉。
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(248.dp)
                        .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(20.dp)),
                )
            }
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
            if (hasCamera && granted && hasFlash) {
                Spacer(Modifier.height(12.dp))
                ScanButton(
                    label = stringResource(R.string.qr_scan_torch),
                    highlighted = torchOn,
                ) { torchOn = !torchOn }
            }
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
                ScanButton(stringResource(android.R.string.cancel), highlighted = false, onBack)
            }
            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
private fun ScanButton(
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                else Color.White.copy(alpha = 0.18f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/**
 * 相机预览与逐帧分析。
 *
 * `KEEP_ONLY_LATEST` 保证回调不被积压；`processingFrame` 防止一帧还没处理完
 * 又送下一帧，`handledScan` 保证同一个码只回调一次。后摄优先，没有后摄的
 * 设备回退前摄，避免整页只剩相册入口。
 */
@Composable
private fun CameraPreview(
    scanner: BarcodeScanner,
    onText: (String) -> Boolean,
    onCamera: (Camera?) -> Unit,
    onFlash: (Boolean) -> Unit,
    onCameraError: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val processingFrame = remember { AtomicBoolean(false) }
    val handledScan = remember { AtomicBoolean(false) }
    val scanActive = remember { AtomicBoolean(true) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner) {
        scanActive.set(true)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        future.addListener({
            val cameraProvider = runCatching { future.get() }.getOrNull()
            if (cameraProvider == null) {
                onCameraError()
                return@addListener
            }
            provider = cameraProvider
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysisUseCase = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysisUseCase.setAnalyzer(executor) { imageProxy ->
                analyzeFrame(
                    imageProxy = imageProxy,
                    scanner = scanner,
                    processingFrame = processingFrame,
                    handledScan = handledScan,
                    scanActive = scanActive,
                    onText = onText,
                )
            }
            analysis = analysisUseCase
            val selector = when {
                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                    CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                    CameraSelector.DEFAULT_FRONT_CAMERA

                else -> null
            }
            if (selector == null) {
                analysisUseCase.clearAnalyzer()
                onCameraError()
                return@addListener
            }
            val camera = runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    analysisUseCase,
                )
            }.getOrElse {
                analysisUseCase.clearAnalyzer()
                onCameraError()
                return@addListener
            }
            cameraRef = camera
            onCamera(camera)
            onFlash(camera.cameraInfo.hasFlashUnit())
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            scanActive.set(false)
            analysis?.clearAnalyzer()
            provider?.unbindAll()
            cameraRef = null
            onCamera(null)
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    val camera = cameraRef ?: return@detectTransformGestures
                    val state = camera.cameraInfo.zoomState.value ?: return@detectTransformGestures
                    val target = (state.zoomRatio * zoomChange).coerceIn(
                        state.minZoomRatio,
                        state.maxZoomRatio,
                    )
                    camera.cameraControl.setZoomRatio(target)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val camera = cameraRef ?: return@detectTapGestures
                    val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                    camera.cameraControl.startFocusAndMetering(
                        FocusMeteringAction.Builder(point).build(),
                    )
                }
            },
    )
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun analyzeFrame(
    imageProxy: androidx.camera.core.ImageProxy,
    scanner: BarcodeScanner,
    processingFrame: AtomicBoolean,
    handledScan: AtomicBoolean,
    scanActive: AtomicBoolean,
    onText: (String) -> Boolean,
) {
    if (!scanActive.get() || handledScan.get() || !processingFrame.compareAndSet(false, true)) {
        imageProxy.close()
        return
    }
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        processingFrame.set(false)
        imageProxy.close()
        return
    }
    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            val text = barcodes.asSequence().mapNotNull { it.rawValue }.firstOrNull()
            if (text != null && scanActive.get() && handledScan.compareAndSet(false, true)) {
                if (!onText(text)) {
                    // 不是配对码：保留相机继续扫，避免一次误识别就锁死页面。
                    handledScan.set(false)
                }
            }
        }
        .addOnCompleteListener {
            processingFrame.set(false)
            imageProxy.close()
        }
}

private suspend fun BarcodeScanner.scan(bitmap: Bitmap): String? =
    suspendCancellableCoroutine { continuation ->
        process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { barcodes ->
                continuation.resume(barcodes.asSequence().mapNotNull { it.rawValue }.firstOrNull())
            }
            .addOnFailureListener { continuation.resume(null) }
    }

/**
 * 相册图片：先按 EXIF 修正方向，再降采样到 1600 像素以内。
 * 直接解码原图在 1 亿像素的手机照片上会占用数百 MB 内存。
 */
private fun decodeGalleryBitmap(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null
    val orientation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    return rotateForExif(bitmap, orientation)
}

private fun rotateForExif(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }

        else -> return bitmap
    }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}
