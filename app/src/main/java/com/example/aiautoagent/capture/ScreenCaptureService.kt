package com.example.aiautoagent.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.aiautoagent.R
import com.example.aiautoagent.util.AgentLogger
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"

        @Volatile private var latest: ByteArray? = null
        @Volatile private var active = false
        fun latestJpeg(): ByteArray? = latest
        fun isActive(): Boolean = active
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var captureRotation = Surface.ROTATION_0
    private var captureWidth = 0
    private var captureHeight = 0
    private var warmupUntil = 0L
    private var recreating = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = NotificationCompat.Builder(this, "capture")
            .setContentTitle("AI Auto Agent")
            .setContentText("Screen capture active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(41, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(41, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection == null) {
            val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val data = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
            } ?: return START_NOT_STICKY
            startCapture(code, data)
        }
        return START_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(code, data)
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { releaseCapture(stopProjection = false); stopSelf() }
            }
        }
        projection?.registerCallback(projectionCallback!!, handler)
        reinitializeForCurrentRotation("initial")
    }

    private fun currentRotation(): Int =
        getSystemService(WindowManager::class.java).defaultDisplay.rotation

    private fun currentRealSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun reinitializeForCurrentRotation(reason: String) {
        if (projection == null || recreating) return
        recreating = true
        try {
            val rotation = currentRotation()
            val (physicalW, physicalH) = currentRealSize()
            val landscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
            val width = if (landscape) maxOf(physicalW, physicalH) else minOf(physicalW, physicalH)
            val height = if (landscape) minOf(physicalW, physicalH) else maxOf(physicalW, physicalH)
            releaseDisplayOnly()
            captureRotation = rotation
            captureWidth = width
            captureHeight = height
            latest = null
            warmupUntil = SystemClock.uptimeMillis() + 150L

            val density = resources.displayMetrics.densityDpi
            val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = newReader
            newReader.setOnImageAvailableListener({ imageReader ->
                val now = SystemClock.uptimeMillis()
                if (now < warmupUntil) {
                    imageReader.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                val observedRotation = currentRotation()
                if (observedRotation != captureRotation) {
                    imageReader.acquireLatestImage()?.close()
                    handler.post { reinitializeForCurrentRotation("rotation changed") }
                    return@setOnImageAvailableListener
                }
                imageReader.acquireLatestImage()?.use { image ->
                    if (!isUsableImage(image)) {
                        AgentLogger.warn("Discarded null/black capture frame")
                    } else {
                        encode(image, width, height)?.let { latest = it }
                    }
                }
            }, handler)

            display = projection?.createVirtualDisplay(
                "AIAgentCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface,
                null,
                handler
            )
            active = display != null
            AgentLogger.info("Capture initialized: ${width}x${height} rotation=$rotation ($reason)")
        } catch (t: Throwable) {
            AgentLogger.error("Capture reinitialization failed", t)
            releaseCapture(stopProjection = true)
        } finally {
            recreating = false
        }
    }

    private fun releaseDisplayOnly() {
        display?.release()
        display = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
    }

    private fun releaseCapture(stopProjection: Boolean) {
        releaseDisplayOnly()
        if (stopProjection) {
            projection?.let { p -> projectionCallback?.let { p.unregisterCallback(it) } }
            projection?.stop()
        }
        projectionCallback = null
        projection = null
        latest = null
        active = false
    }

    private fun isUsableImage(image: Image): Boolean {
        if (image.width <= 0 || image.height <= 0 || image.planes.isEmpty()) return false
        val buffer = image.planes[0].buffer.duplicate()
        if (!buffer.hasRemaining()) return false
        val sampleCount = minOf(64, buffer.remaining() / 4)
        if (sampleCount <= 0) return false
        var nonZero = 0
        for (i in 0 until sampleCount) {
            val base = i * 4
            if (base + 2 >= buffer.limit()) break
            if (buffer.get(base).toInt() != 0 || buffer.get(base + 1).toInt() != 0 || buffer.get(base + 2).toInt() != 0) nonZero++
        }
        return nonZero > 0
    }

    private fun encode(img: Image, width: Int, height: Int): ByteArray? {
        return try {
            val plane = img.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = if (paddedWidth != width) Bitmap.createBitmap(bitmap, 0, 0, width, height) else bitmap
            val maxWidth = 720
            val scaled = if (cropped.width > maxWidth) {
                val newHeight = (cropped.height.toFloat() * maxWidth / cropped.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(cropped, maxWidth, newHeight, true)
            } else cropped
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, output)
            if (scaled !== cropped) scaled.recycle()
            if (cropped !== bitmap) cropped.recycle()
            bitmap.recycle()
            output.toByteArray()
        } catch (t: Throwable) {
            AgentLogger.error("Frame encode failed", t)
            null
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("capture", "Screen Capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        releaseCapture(stopProjection = true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
