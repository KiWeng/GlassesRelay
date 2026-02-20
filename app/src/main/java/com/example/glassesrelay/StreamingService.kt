package com.example.glassesrelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import java.io.ByteArrayOutputStream
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpStream
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that bridges Meta DAT SDK video frames to an RTMP server.
 *
 * Pipeline: Ray-Ban Glasses → BT → DAT SDK StreamSession → VideoFrame Flow
 *           → Bitmap decode → Canvas draw to encoder surface
 *           → MediaCodec H.264 → RtmpStream → RTMP server
 */
class StreamingService : Service(), ConnectChecker {

    companion object {
        private const val TAG = "StreamingService"
        private const val CHANNEL_ID = "glasses_relay_stream"
        private const val NOTIFICATION_ID = 1
        private const val VIDEO_WIDTH = 720
        private const val VIDEO_HEIGHT = 1280
        private const val VIDEO_BITRATE = 2_500_000  // 2.5 Mbps
        private const val VIDEO_FPS = 30
        private const val AUDIO_SAMPLE_RATE = 44100

        const val EXTRA_RTMP_URL = "extra_rtmp_url"
        const val EXTRA_VIDEO_QUALITY = "extra_video_quality" // "LOW", "MEDIUM", "HIGH"
        const val EXTRA_VIDEO_FPS = "extra_video_fps"
        const val ACTION_STOP = "com.example.glassesrelay.STOP_STREAM"
    }

    // ── Service binding ─────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        val service: StreamingService get() = this@StreamingService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ── State exposed to Activity ───────────────────────────────────
    data class StreamState(
        val isStreaming: Boolean = false,
        val isConnecting: Boolean = false,
        val fps: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val statusMessage: String = "Idle",
        val errorMessage: String? = null,
        val latestFrame: Bitmap? = null
    )

    private val _streamState = MutableStateFlow(StreamState())
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    // ── Internals ───────────────────────────────────────────────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var rtmpStream: RtmpStream? = null
    private var streamSession: StreamSession? = null
    private var frameCollectorJob: Job? = null
    private var fpsCounterJob: Job? = null
    private var frameCount = 0L
    private var lastFpsTimestamp = 0L

    private var currentQuality: String = "HIGH"
    private var currentFps: Int = 30

    // ─────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStreaming()
            stopSelf()
            return START_NOT_STICKY
        }

        val rtmpUrl = intent?.getStringExtra(EXTRA_RTMP_URL)
        val qualityStr = intent?.getStringExtra(EXTRA_VIDEO_QUALITY) ?: "HIGH"
        val fps = intent?.getIntExtra(EXTRA_VIDEO_FPS, 30) ?: 30

        if (!isValidRtmpUrl(rtmpUrl)) {
            Log.e(TAG, "Invalid or missing RTMP URL")
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID, 
                buildNotification("Connecting…"), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                buildNotification("Connecting…"), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))
        }
        startStreaming(rtmpUrl, qualityStr, fps)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopStreaming()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────
    //  Streaming pipeline
    // ─────────────────────────────────────────────────────────────────

    private fun startStreaming(rtmpUrl: String, qualityStr: String, fps: Int) {
        if (_streamState.value.isStreaming || _streamState.value.isConnecting) return

        currentQuality = qualityStr
        currentFps = fps

        _streamState.value = StreamState(isConnecting = true, statusMessage = "Initializing Camera…")

        // Skip RTMP for now, just start the camera directly
        startDatCameraSession(qualityStr, fps)
    }

    /**
     * After RTMP connection is established, start the DAT camera session
     * and begin relaying frames.
     */
    private fun startDatCameraSession(qualityStr: String, fps: Int) {
        serviceScope.launch {
            try {
                // Check if glasses are connected
                val deviceIds = Wearables.devices.value
                if (deviceIds.isEmpty()) {
                    _streamState.value = StreamState(errorMessage = "No glasses connected")
                    stopStreaming()
                    stopSelf()
                    return@launch
                }

                val quality = when (qualityStr.uppercase()) {
                    "LOW" -> VideoQuality.LOW
                    "MEDIUM" -> VideoQuality.MEDIUM
                    else -> VideoQuality.HIGH
                }

                // Configure stream based on user settings
                val config = StreamConfiguration(
                    videoQuality = quality,
                    frameRate = fps
                )

                Log.d(TAG, "Starting stream with Quality: $quality, FPS: $fps")

                // Start stream session using AutoDeviceSelector
                val session = Wearables.startStreamSession(
                    this@StreamingService,
                    AutoDeviceSelector(),
                    config
                )
                streamSession = session

                _streamState.value = StreamState(
                    isStreaming = true,
                    statusMessage = "LIVE"
                )
                updateNotification("🔴 Streaming LIVE")

                // Observe the stream session state to handle unexpected disconnects
                serviceScope.launch {
                    session.state.collect { state ->
                        Log.d(TAG, "Stream session state changed: $state")
                        val stateStr = state.toString()
                        if (stateStr == "CLOSED" || stateStr == "ERROR") {
                            _streamState.value = _streamState.value.copy(
                                isStreaming = false,
                                errorMessage = "Streaming stopped by glasses ($stateStr)",
                                statusMessage = "Disconnected"
                            )
                            stopStreaming()
                        }
                    }
                }

                // Start FPS counter
                lastFpsTimestamp = System.currentTimeMillis()
                frameCount = 0L
                fpsCounterJob = serviceScope.launch {
                    while (true) {
                        delay(1000L)
                        val now = System.currentTimeMillis()
                        val elapsed = (now - lastFpsTimestamp) / 1000.0
                        val currentFps = if (elapsed > 0) (frameCount / elapsed).toInt() else 0
                        _streamState.value = _streamState.value.copy(fps = currentFps)
                        frameCount = 0
                        lastFpsTimestamp = now
                    }
                }

                // Collect video frames and relay to RTMP encoder
                frameCollectorJob = serviceScope.launch(Dispatchers.Default) {
                    session.videoStream.collect { videoFrame: VideoFrame ->
                        relayVideoFrame(videoFrame)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "DAT camera session failed", e)
                _streamState.value = StreamState(errorMessage = "Camera session: ${e.message}")
                stopStreaming()
                stopSelf()
            }
        }
    }

    /**
     * Decode a VideoFrame's ByteBuffer into a Bitmap and expose it
     * via the Flow for the UI to preview.
     */
    private fun relayVideoFrame(videoFrame: VideoFrame) {
        try {
            // VideoFrame contains raw I420 video data in a ByteBuffer
            val buffer = videoFrame.buffer
            val dataSize = buffer.remaining()
            val byteArray = ByteArray(dataSize)

            // Save current position
            val originalPosition = buffer.position()
            buffer.get(byteArray)
            // Restore position
            buffer.position(originalPosition)

            // Convert I420 to NV21 format which is supported by Android's YuvImage
            val nv21 = convertI420toNV21(byteArray, videoFrame.width, videoFrame.height)
            val image = YuvImage(nv21, ImageFormat.NV21, videoFrame.width, videoFrame.height, null)
            val out = ByteArrayOutputStream().use { stream ->
                image.compressToJpeg(Rect(0, 0, videoFrame.width, videoFrame.height), 50, stream)
                stream.toByteArray()
            }

            val bitmap = BitmapFactory.decodeByteArray(out, 0, out.size) ?: return

            // Post bitmap and metadata to StateFlow for UI to render
            _streamState.value = _streamState.value.copy(
                latestFrame = bitmap,
                width = videoFrame.width,
                height = videoFrame.height
            )
            frameCount++

        } catch (e: Exception) {
            Log.w(TAG, "Frame decode error", e)
        }
    }

    // Convert I420 (YYYYYYYY:UUVV) to NV21 (YYYYYYYY:VUVU)
    private fun convertI420toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
        val output = ByteArray(input.size)
        val size = width * height
        val quarter = size / 4

        input.copyInto(output, 0, 0, size) // Y is the same

        for (n in 0 until quarter) {
            output[size + n * 2] = input[size + quarter + n] // V first
            output[size + n * 2 + 1] = input[size + n] // U second
        }
        return output
    }

    fun stopStreaming() {
        frameCollectorJob?.cancel()
        frameCollectorJob = null
        fpsCounterJob?.cancel()
        fpsCounterJob = null

        try {
            streamSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing stream session", e)
        }
        streamSession = null

        try {
            rtmpStream?.stopStream()
            rtmpStream?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping RTMP stream", e)
        }
        rtmpStream = null

        // Only update status to "Stopped" if we didn't just set an error message
        if (_streamState.value.errorMessage == null) {
            _streamState.value = StreamState(statusMessage = "Stopped")
        } else {
            _streamState.value = _streamState.value.copy(isStreaming = false, isConnecting = false)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  ConnectChecker (RTMP connection callbacks)
    // ─────────────────────────────────────────────────────────────────

    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "RTMP connecting to: $url")
        _streamState.value = StreamState(isConnecting = true, statusMessage = "Connecting…")
    }

    override fun onConnectionSuccess() {
        Log.d(TAG, "RTMP connected successfully")
        // RTMP link is up → start capturing from glasses
        startDatCameraSession(currentQuality, currentFps)
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "RTMP connection failed: $reason")
        _streamState.value = StreamState(errorMessage = "RTMP failed: $reason")
        updateNotification("Connection failed")
    }

    override fun onNewBitrate(bitrate: Long) {
        Log.d(TAG, "Bitrate: ${bitrate / 1000} kbps")
    }

    override fun onDisconnect() {
        Log.d(TAG, "RTMP disconnected")
        _streamState.value = StreamState(statusMessage = "Disconnected")
        updateNotification("Disconnected")
        stopStreaming()
        stopSelf()
    }

    override fun onAuthError() {
        Log.e(TAG, "RTMP auth error")
        _streamState.value = StreamState(errorMessage = "RTMP authentication error")
    }

    override fun onAuthSuccess() {
        Log.d(TAG, "RTMP auth success")
    }

    // ─────────────────────────────────────────────────────────────────
    //  Notification
    // ─────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GlassesRelay Stream",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while streaming video from glasses to RTMP server"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, StreamingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("GlassesRelay")
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop", stopIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
