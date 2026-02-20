package com.example.glassesrelay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import com.pedro.encoder.input.sources.video.VideoSource

/**
 * A custom [VideoSource] that draws dynamically pushed bitmaps to the encoder's
 * SurfaceTexture. Unlike [BitmapSource] which loops a single static image,
 * this source renders a new frame each time [pushBitmap] is called.
 *
 * Thread-safety: [pushBitmap] may be called from any thread. The Surface
 * lockCanvas/unlockCanvasAndPost pair is inherently thread-safe.
 */
class DynamicBitmapSource : VideoSource() {

    companion object {
        private const val TAG = "DynamicBitmapSource"
    }

    @Volatile
    private var running = false
    private var surface: Surface? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstRect = Rect()

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        Log.d(TAG, "create: ${width}x${height} @ ${fps}fps")
        return true
    }

    override fun start(surfaceTexture: SurfaceTexture) {
        surfaceTexture.setDefaultBufferSize(width, height)
        surface = Surface(surfaceTexture)
        running = true
        Log.d(TAG, "started: surface ready for ${width}x${height}")
    }

    override fun stop() {
        running = false
        surface?.release()
        surface = null
        Log.d(TAG, "stopped")
    }

    override fun release() {
        // No owned resources to release beyond the surface (handled in stop)
    }

    override fun isRunning(): Boolean = running

    /**
     * Draw [bitmap] to the encoder surface as one video frame.
     * The bitmap is scaled to fill the encoder dimensions.
     * Safe to call from any thread.
     */
    fun pushBitmap(bitmap: Bitmap) {
        val s = surface ?: return
        if (!running) return
        try {
            val canvas = s.lockCanvas(null) ?: return
            try {
                dstRect.set(0, 0, canvas.width, canvas.height)
                canvas.drawBitmap(bitmap, null, dstRect, paint)
            } finally {
                s.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.w(TAG, "pushBitmap failed: ${e.message}")
        }
    }
}
