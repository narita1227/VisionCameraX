package com.example.visioncamerax

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import com.example.visioncamerax.network.ServerConfig
import com.example.visioncamerax.network.VideoWebSocketManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {

    private val wsManager = VideoWebSocketManager()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sentFrameCount = AtomicLong(0)
    private val lastEncodeMs = AtomicLong(0)
    private val lastSendMs = AtomicLong(0)
    private var hasCameraPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasCameraPermission = hasCameraPermission()
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            val context = LocalContext.current
            var statusText by remember { mutableStateOf("未接続") }
            var sendFps by remember { mutableStateOf("-") }
            var latencyMs by remember { mutableStateOf("-") }
            var profileText by remember { mutableStateOf("変換ms: - / 送信ms: -") }
            var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
            var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
            val previewView = remember {
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            }

            LaunchedEffect(Unit) {
                wsManager.start(
                    onStatus = { status -> statusText = status },
                    onResult = { bitmap, l ->
                        processedBitmap = bitmap
                        latencyMs = l
                    }
                )
            }

            LaunchedEffect(hasCameraPermission, lensFacing) {
                if (hasCameraPermission) {
                    bindCameraUseCases(previewView, lensFacing)
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { previewView }
                )

                processedBitmap?.let { bitmap ->
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Processed frame",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "状態: $statusText", color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "送信FPS: $sendFps", color = Color.White)
                        Text(text = "処理遅延(ms): $latencyMs", color = Color.White)
                    }
                    Text(text = profileText, color = Color.White)
                    Text(
                        text = "カメラ: ${if (lensFacing == CameraSelector.LENS_FACING_BACK) "背面" else "前面"}",
                        color = Color.White,
                    )
                    Button(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                    ) {
                        Text("カメラ切替")
                    }
                    if (!hasCameraPermission) {
                        Text(text = "カメラ権限が必要です", color = Color.White)
                    }
                }
            }

            LaunchedEffect(Unit) {
                var last = System.currentTimeMillis()
                var lastCount = 0L
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    val now = System.currentTimeMillis()
                    val count = sentFrameCount.get()
                    val diff = count - lastCount
                    val sec = (now - last).coerceAtLeast(1)
                    sendFps = String.format("%.1f", diff * 1000.0 / sec)
                    profileText = "変換ms: ${lastEncodeMs.get()} / 送信ms: ${lastSendMs.get()}"
                    last = now
                    lastCount = count
                }
            }
        }
    }

    private fun bindCameraUseCases(previewView: PreviewView, lensFacing: Int) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    val nowMs = System.currentTimeMillis()
                    val mirrorHorizontally = lensFacing == CameraSelector.LENS_FACING_FRONT
                    val encodeStartNs = System.nanoTime()
                    val jpeg = imageProxyToJpeg(imageProxy, mirrorHorizontally) ?: return@setAnalyzer
                    val encodeMs = (System.nanoTime() - encodeStartNs) / 1_000_000

                    val sendStartNs = System.nanoTime()
                    wsManager.sendFrame(jpeg, nowMs)
                    val sendMs = (System.nanoTime() - sendStartNs) / 1_000_000

                    lastEncodeMs.set(encodeMs)
                    lastSendMs.set(sendMs)
                    sentFrameCount.incrementAndGet()
                } finally {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageProxyToJpeg(image: ImageProxy, mirrorHorizontally: Boolean): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null

        val nv21 = yuv420888ToNv21(image)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val tmp = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 70, tmp)
        return transformJpegIfNeeded(
            tmp.toByteArray(),
            image.imageInfo.rotationDegrees,
            mirrorHorizontally,
        )
    }

    private fun transformJpegIfNeeded(
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean,
    ): ByteArray {
        if (rotationDegrees == 0 && !mirrorHorizontally) return jpegBytes

        val bitmap = android.graphics.BitmapFactory.decodeByteArray(
            jpegBytes,
            0,
            jpegBytes.size,
        ) ?: return jpegBytes

        var transformed = bitmap
        if (rotationDegrees != 0) {
            val rotationMatrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(
                transformed,
                0,
                0,
                transformed.width,
                transformed.height,
                rotationMatrix,
                true,
            )
            if (rotated !== transformed) {
                transformed.recycle()
                transformed = rotated
            }
        }

        if (mirrorHorizontally) {
            val mirrorMatrix = Matrix().apply { preScale(-1f, 1f) }
            val mirrored = Bitmap.createBitmap(
                transformed,
                0,
                0,
                transformed.width,
                transformed.height,
                mirrorMatrix,
                true,
            )
            if (mirrored !== transformed) {
                transformed.recycle()
                transformed = mirrored
            }
        }

        val out = ByteArrayOutputStream()
        transformed.compress(Bitmap.CompressFormat.JPEG, 70, out)
        transformed.recycle()
        return out.toByteArray()
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height * 3 / 2)

        copyPlane(
            planeBuffer = image.planes[0].buffer,
            rowStride = image.planes[0].rowStride,
            pixelStride = image.planes[0].pixelStride,
            width = width,
            height = height,
            out = out,
            outOffset = 0,
            outPixelStride = 1,
        )

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        var outputOffset = width * height
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uPlane.rowStride
            val vRowStart = row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                out[outputOffset++] = vPlane.buffer.get(vRowStart + col * vPlane.pixelStride)
                out[outputOffset++] = uPlane.buffer.get(uRowStart + col * uPlane.pixelStride)
            }
        }

        return out
    }

    private fun copyPlane(
        planeBuffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        out: ByteArray,
        outOffset: Int,
        outPixelStride: Int,
    ) {
        var outputOffset = outOffset
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) {
                out[outputOffset] = planeBuffer.get(rowStart + col * pixelStride)
                outputOffset += outPixelStride
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        wsManager.stop()
        cameraExecutor.shutdown()
    }
}
