package com.dronevision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * Foreground service that captures the screen, runs the classifier on a
 * throttled schedule, and feeds boxes to the overlay. Designed defensively:
 *  - runs as a foreground service (survives on Xiaomi/Realme/Oppo/Vivo)
 *  - throttles inference to ~2 fps (protects budget phones)
 *  - downscales frames before inference
 *  - releases every Image buffer in finally (avoids "maxImages" crash)
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "capture_channel"
        private const val NOTIF_ID = 42
        private const val DETECT_INTERVAL_MS = 450L      // ~2 fps
        private const val CLEAR_SETTLE_MS = 80L          // overlay redraw time
        private const val ANALYSIS_MAX_WIDTH = 640        // downscale target

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.dronevision.STOP"

        // Set by MainActivity so the overlay can be toggled and last frame saved.
        @Volatile var lastAnnotatedFrame: Bitmap? = null
        @Volatile var lastDetections: List<Detection> = emptyList()
        @Volatile var running: Boolean = false
        private val frameLock = Any()

        /** Thread-safe copy of the latest annotated frame for saving. */
        fun snapshotFrame(): Bitmap? = synchronized(frameLock) {
            lastAnnotatedFrame?.let {
                if (!it.isRecycled) it.copy(Bitmap.Config.ARGB_8888, false) else null
            }
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var overlay: OverlayManager
    private lateinit var classifier: DiseaseClassifier

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var lastDetectTime = 0L
    private var clearRequestedAt = 0L

    private var screenW = 0
    private var screenH = 0
    private var screenDensity = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayManager(this)
        classifier = DiseaseClassifier(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= 33)
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else
                @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultData == null) {
            Log.e(TAG, "No projection data; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, resultData)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopEverything() }
        }, Handler(mainLooper))

        setupCapture()
        overlay.show()
        running = true
        return START_STICKY
    }

    private fun setupCapture() {
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        screenDensity = metrics.densityDpi

        bgThread = HandlerThread("capture").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)

        imageReader = ImageReader.newInstance(
            screenW, screenH, PixelFormat.RGBA_8888, 2
        )
        imageReader!!.setOnImageAvailableListener({ reader ->
            onFrame(reader)
        }, bgHandler)

        virtualDisplay = projection?.createVirtualDisplay(
            "dronevision",
            screenW, screenH, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, bgHandler
        )
    }

    private fun onFrame(reader: ImageReader) {
        val now = System.currentTimeMillis()
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
        if (image == null) return
        try {
            // Anti-feedback sequence: our own boxes must not appear in the
            // frame we classify. Step 1: when it's time to detect, clear the
            // overlay and wait. Step 2: after the overlay has redrawn empty,
            // the next frame is clean -> run inference on it, then draw boxes.
            if (clearRequestedAt == 0L) {
                if (now - lastDetectTime < DETECT_INTERVAL_MS) return
                overlay.update(emptyList())   // hide boxes (brief flicker)
                clearRequestedAt = now
                return
            }
            if (now - clearRequestedAt < CLEAR_SETTLE_MS) return // overlay still redrawing
            clearRequestedAt = 0L
            lastDetectTime = now

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenW

            val bmp = Bitmap.createBitmap(
                screenW + rowPadding / pixelStride, screenH, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            val frame = Bitmap.createBitmap(bmp, 0, 0, screenW, screenH)
            bmp.recycle()

            // Downscale for inference to protect low-end phones.
            val analysis = if (frame.width > ANALYSIS_MAX_WIDTH) {
                val scale = ANALYSIS_MAX_WIDTH.toFloat() / frame.width
                Bitmap.createScaledBitmap(
                    frame, ANALYSIS_MAX_WIDTH, (frame.height * scale).toInt(), true
                )
            } else frame

            val rawDetections = classifier.detect(analysis)

            // Map detection coords from the analysis bitmap back to screen pixels.
            val sx = screenW.toFloat() / analysis.width
            val sy = screenH.toFloat() / analysis.height
            val screenDetections = rawDetections.map {
                it.copy(
                    left = (it.left * sx).toInt(), top = (it.top * sy).toInt(),
                    right = (it.right * sx).toInt(), bottom = (it.bottom * sy).toInt()
                )
            }

            overlay.update(screenDetections)
            lastDetections = screenDetections
            // Keep a copy for the Save button (frame + drawn boxes).
            val annotated = annotate(frame, screenDetections)
            synchronized(frameLock) {
                lastAnnotatedFrame?.recycle()
                lastAnnotatedFrame = annotated
            }

            if (analysis != frame) analysis.recycle()
            frame.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "frame processing error; skipping frame", e)
        } finally {
            // ALWAYS release the image or the reader stalls and crashes.
            try { image.close() } catch (_: Exception) { }
        }
    }

    /** Draw boxes onto a copy of the frame so it can be saved with annotations. */
    private fun annotate(frame: Bitmap, dets: List<Detection>): Bitmap {
        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val box = Paint().apply {
            color = Color.parseColor("#FF3B30"); style = Paint.Style.STROKE; strokeWidth = 6f
        }
        val txt = Paint().apply { color = Color.WHITE; textSize = 34f }
        val txtBg = Paint().apply { color = Color.parseColor("#CC000000") }
        for (d in dets) {
            canvas.drawRect(d.left.toFloat(), d.top.toFloat(), d.right.toFloat(), d.bottom.toFloat(), box)
            val label = "${d.label} ${(d.confidence * 100).toInt()}%"
            val tw = txt.measureText(label)
            val ty = (d.top - 12).coerceAtLeast(38).toFloat()
            canvas.drawRect(d.left.toFloat(), ty - 34, d.left + tw + 16, ty + 8, txtBg)
            canvas.drawText(label, d.left + 8f, ty, txt)
        }
        return out
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Drone Vision Capture", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Drone Vision running")
            .setContentText("Analysing screen for crop disease")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun stopEverything() {
        running = false
        try { overlay.hide() } catch (_: Exception) { }
        try { virtualDisplay?.release() } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        try { projection?.stop() } catch (_: Exception) { }
        try { classifier.close() } catch (_: Exception) { }
        bgThread?.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }
}
