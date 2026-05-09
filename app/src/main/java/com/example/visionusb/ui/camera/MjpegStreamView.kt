package com.example.visionusb.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MjpegStreamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var latestBitmap: Bitmap? = null

    @Volatile
    private var isStreaming = false

    fun startStream(url: String) {
        if (isStreaming) return
        isStreaming = true

        executor.execute {
            while (isStreaming) {
                try {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body
                        if (!response.isSuccessful || body == null) {
                            sleepBeforeRetry()
                            return@use
                        }

                        val input = BufferedInputStream(body.byteStream())
                        while (isStreaming) {
                            val jpegBytes = readNextJpegFrame(input) ?: break
                            val bitmap = BitmapFactory.decodeByteArray(
                                jpegBytes,
                                0,
                                jpegBytes.size
                            ) ?: continue

                            replaceBitmap(bitmap)
                            postInvalidate()
                        }
                    }
                } catch (_: Exception) {
                }

                if (isStreaming) {
                    sleepBeforeRetry()
                }
            }
        }
    }

    fun stopStream() {
        isStreaming = false
        recycleLatestBitmap()
        postInvalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopStream()
        executor.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = latestBitmap ?: return
        canvas.drawBitmap(
            bitmap,
            null,
            android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()),
            null
        )
    }

    private fun replaceBitmap(newBitmap: Bitmap) {
        val oldBitmap = latestBitmap
        latestBitmap = newBitmap
        oldBitmap?.recycle()
    }

    private fun recycleLatestBitmap() {
        latestBitmap?.recycle()
        latestBitmap = null
    }

    private fun sleepBeforeRetry() {
        try {
            TimeUnit.SECONDS.sleep(2)
        } catch (_: InterruptedException) {
        }
    }

    private fun readNextJpegFrame(input: BufferedInputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()

        var prev = -1
        var curr: Int

        while (true) {
            curr = input.read()
            if (curr == -1) return null

            if (prev == 0xFF && curr == 0xD8) {
                buffer.write(0xFF)
                buffer.write(0xD8)
                break
            }
            prev = curr
        }

        prev = -1

        while (true) {
            curr = input.read()
            if (curr == -1) return null

            buffer.write(curr)

            if (prev == 0xFF && curr == 0xD9) {
                return buffer.toByteArray()
            }
            prev = curr
        }
    }
}
