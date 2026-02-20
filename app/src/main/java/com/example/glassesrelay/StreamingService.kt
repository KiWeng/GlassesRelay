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
import android.graphics.Rect
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
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
        val statusMessage: String = "Idle",
        val errorMessage: String? = null
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
        if (rtmpUrl.isNullOrBlank()) {
            Log.e(TAG, "No RTMP URL provided")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))
        startStreaming(rtmpUrl)
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

    private fun startStreaming(rtmpUrl: String) {
        if (_streamState.value.isStreaming || _streamState.value.isConnecting) return

        _streamState.value = StreamState(isConnecting = true, statusMessage = "Initializing…")

        try {
            // 1. Create RtmpStream with NoVideoSource (we'll draw frames manually)
            //    and NoAudioSource (glasses stream is video-only for RTMP relay)
            val stream = RtmpStream(
                this,
                this,
                NoVideoSource(),   // video: we draw frames to the encoder surface manually
                NoAudioSource()    // audio: no audio needed for video relay
            ).also {
                rtmpStream = it
            }

            // 2. Prepare video encoder: 720×1280 @ 2.5 Mbps, 30 FPS
            val videoPrepared = stream.prepareVideo(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_BITRATE)
            // Prepare audio with NoAudioSource (required by RtmpStream even if unused)
            stream.prepareAudio(AUDIO_SAMPLE_RATE, true, 128_000)

            if (!videoPrepared) {
                _streamState.value = StreamState(errorMessage = "Failed to prepare video encoder")
                stopSelf()
                return
            }

            // 3. Connect to RTMP server (async — onConnectionSuccess will start camera)
            _streamState.value = StreamState(isConnecting = true, statusMessage = "Connecting to RTMP…")
            stream.startStream(rtmpUrl)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming", e)
            _streamState.value = StreamState(errorMessage = "Init failed: ${e.message}")
            stopSelf()
        }
    }

    /**
     * After RTMP connection is established, start the DAT camera session
     * and begin relaying frames.
     */
    private fun startDatCameraSession() {
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

                // Configure stream: HIGH quality (720x1280), 30 FPS
                val config = StreamConfiguration(
                    videoQuality = VideoQuality.HIGH,
                    frameRate = VIDEO_FPS
                )

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
     * Decode a VideoFrame's ByteBuffer into a Bitmap and draw it onto
     * the encoder's input surface via Canvas.
     *
     * VideoFrame contains raw image data as a ByteBuffer.
     * We decode it to a Bitmap and draw to the MediaCodec input surface.
     */
    private fun relayVideoFrame(videoFrame: VideoFrame) {
        try {
            val stream = rtmpStream ?: return

            // Decode VideoFrame's ByteBuffer to Bitmap
            val buffer = videoFrame.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            buffer.rewind()

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return

            // Draw bitmap onto the encoder's GL input surface
            val glInterface = stream.getGlInterface()
            val encoderSurface = glInterface.getSurfaceTexture()
            val surface = Surface(encoderSurface)
            try {
                val canvas = surface.lockCanvas(null)
                if (canvas != null) {
                    canvas.drawBitmap(
                        bitmap,
                        null,
                        Rect(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT),
                        null
                    )
                    surface.unlockCanvasAndPost(canvas)
                    frameCount++
                }
            } finally {
                surface.release()
            }

            bitmap.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Frame relay error", e)
        }
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

        _streamState.value = StreamState(statusMessage = "Stopped")
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
        startDatCameraSession()
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
