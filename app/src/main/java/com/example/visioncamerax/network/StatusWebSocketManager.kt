package com.example.visioncamerax.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class VideoWebSocketManager {

    companion object {
        private const val TAG = "VideoWebSocketManager"
        private const val RECONNECT_BASE_DELAY_MS = 1000L
        private const val RECONNECT_MAX_DELAY_MS = 15000L
    }

    private val client = OkHttpClient()
    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameSeq = AtomicLong(0L)

    @Volatile
    private var currentWebSocket: WebSocket? = null

    @Volatile
    private var workerThread: Thread? = null

    fun start(
        onStatus: (String) -> Unit,
        onResult: (bitmap: Bitmap, latencyMs: String) -> Unit
    ) {
        if (running.getAndSet(true)) return

        workerThread = Thread {
            var reconnectDelayMs = RECONNECT_BASE_DELAY_MS
            while (running.get()) {
                try {
                    if (connected.get()) {
                        Thread.sleep(500)
                        continue
                    }

                    val targetUrl = ServerConfig.videoWsUrl()
                    postStatus(onStatus, "接続試行中: $targetUrl")
                    closeCurrentSocketQuietly()

                    val request = Request.Builder()
                        .url(targetUrl)
                        .build()

                    val listener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            connected.set(true)
                            reconnectDelayMs = RECONNECT_BASE_DELAY_MS
                            postStatus(onStatus, "接続中")
                            Log.i(TAG, "WebSocket connected")
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            try {
                                val json = JSONObject(text)
                                val payload = json.optJSONObject("payload") ?: return
                                val jpegBase64 = payload.optString("jpeg_base64", "")
                                if (jpegBase64.isEmpty()) return

                                val bytes = Base64.decode(jpegBase64, Base64.DEFAULT)
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
                                val latency = payload.optDouble("process_latency_ms", 0.0)

                                mainHandler.post {
                                    onResult(
                                        bitmap,
                                        String.format("%.1f", latency)
                                    )
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse/process message", e)
                            }
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            connected.set(false)
                            currentWebSocket = null
                            postStatus(onStatus, "接続待ち (closed:$code)")
                            Log.i(TAG, "WebSocket closed code=$code reason=$reason")
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            connected.set(false)
                            currentWebSocket = null
                            Log.w(TAG, "WebSocket failure", t)
                            val code = response?.code
                            if (code != null) {
                                postStatus(onStatus, "接続待ち (error:$code)")
                            } else {
                                postStatus(onStatus, "接続待ち (${t.javaClass.simpleName})")
                            }
                        }
                    }

                    currentWebSocket = client.newWebSocket(request, listener)
                    Thread.sleep(reconnectDelayMs)
                    reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                } catch (e: Exception) {
                    connected.set(false)
                    Log.w(TAG, "Reconnect loop failure", e)
                    postStatus(onStatus, "接続待ち")
                    Thread.sleep(reconnectDelayMs)
                    reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                }
            }
        }.apply {
            name = "VideoWebSocketWorker"
            isDaemon = true
            start()
        }
    }

    fun sendFrame(jpegBytes: ByteArray, captureTimestampMs: Long) {
        val ws = currentWebSocket ?: return
        val payload = JSONObject().apply {
            put("jpeg_base64", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
            put("capture_ts_ms", captureTimestampMs)
        }

        val envelope = JSONObject().apply {
            put("type", "video_frame")
            put("source", "android")
            put("timestamp_ms", System.currentTimeMillis())
            put("seq", frameSeq.incrementAndGet())
            put("payload", payload)
        }

        val sent = ws.send(envelope.toString())
        if (!sent) {
            Log.w(TAG, "sendFrame failed: websocket send returned false")
        }
    }

    fun stop() {
        running.set(false)
        connected.set(false)
        workerThread?.interrupt()
        workerThread = null
        closeCurrentSocketQuietly()
    }

    private fun closeCurrentSocketQuietly() {
        try {
            currentWebSocket?.close(1000, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close websocket", e)
        }
        currentWebSocket = null
    }

    private fun postStatus(onStatus: (String) -> Unit, status: String) {
        mainHandler.post {
            onStatus(status)
        }
    }
}
