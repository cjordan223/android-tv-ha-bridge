package nz.mcnabb.atvhabridge

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream

/**
 * Holds ONE MediaProjection and ONE VirtualDisplay while anyone watches. Android 14 allows a
 * single createVirtualDisplay per projection, only within 5 minutes of consent, and only after
 * a callback is registered — so the display is made the moment the grant arrives and never
 * re-made. When a grant can come back by itself ([ScreenGrantActivity.canAutoGrant]),
 * [IDLE_STOP_MS] after the last viewer the whole projection stops, taking the TV's screen-capture
 * indicator with it, and the next viewer gets a fresh grant. Otherwise the grant is kept: getting
 * it back would mean the onboarding step on the TV again.
 *
 * Idle costs ~nothing: with nobody watching, the display's surface is detached
 * (VirtualDisplay.setSurface(null) — "a similar effect to turning off the screen"), so nothing
 * is composited into it. A watcher re-attaches the ImageReader: a screen.mjpeg client for as long
 * as it's connected (addClient/removeClient), a /screen.jpg pull for [SNAPSHOT_LINGER_MS].
 *
 * Deliberately PULL-based: the ImageReader is never given a listener. Frames are only
 * acquired+JPEG-encoded when latestJpeg() is called, which BridgeServer does at a fixed,
 * throttled rate while serving a stream (~2-3fps) or once per /screen.jpg. A TV box has no CPU
 * margin to spare — encoding every frame starves video playback. If no new frame is ready since the last
 * pull (static content), the previous JPEG is re-served rather than encoding needlessly.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0
    @Volatile private var lastJpeg: ByteArray? = null
    private var activeClients = 0
    private var lastSnapshotMs = 0L
    private var attached = false
    private val detachIfIdle = Runnable { synchronized(this@ScreenCaptureService) { updateSurfaceLocked() } }
    private val stopIfIdle = Runnable {
        if (synchronized(this@ScreenCaptureService) { attached }) return@Runnable
        Log.i(TAG, "nobody watching — stopping capture (and the TV's capture indicator)")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always go foreground first: a startForegroundService() that stops without it crashes
        // the whole process ("did not then call Service.startForeground()").
        startForeground(NOTIF_ID, buildNotification())
        if (projection != null) return START_NOT_STICKY // already capturing
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val proj = if (resultCode == Activity.RESULT_OK && data != null) {
            runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data) }
                .onFailure { Log.w(TAG, "projection refused: ${it.message}") }.getOrNull()
        } else null
        if (proj == null) {
            Log.w(TAG, "started without a usable projection grant; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped by the system")
                stopSelf()
            }
        }, captureHandler)
        projection = proj
        try {
            startCapture(proj)
        } catch (e: Exception) {
            Log.e(TAG, "virtual display failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        instance = this
        Log.i(TAG, "MediaProjection granted; display held, idle until a client connects")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        synchronized(this) {
            captureHandler.removeCallbacks(detachIfIdle)
            captureHandler.removeCallbacks(stopIfIdle)
            virtualDisplay?.release(); virtualDisplay = null
            imageReader?.close(); imageReader = null
            lastJpeg = null
        }
        projection?.stop()
        projection = null
    }

    /** BridgeServer calls this when a screen.mjpeg client connects. */
    @Synchronized
    fun addClient() {
        activeClients++
        updateSurfaceLocked()
    }

    /** BridgeServer calls this on client disconnect (including onException). */
    @Synchronized
    fun removeClient() {
        activeClients = (activeClients - 1).coerceAtLeast(0)
        updateSurfaceLocked()
    }

    /** One frame for /screen.jpg; keeps the surface attached for [SNAPSHOT_LINGER_MS] so a
     * viewer polling a couple of times a second doesn't re-attach on every pull. */
    fun snapshot(): ByteArray? {
        synchronized(this) {
            lastSnapshotMs = SystemClock.elapsedRealtime()
            updateSurfaceLocked()
            captureHandler.removeCallbacks(detachIfIdle)
            captureHandler.postDelayed(detachIfIdle, SNAPSHOT_LINGER_MS)
        }
        return awaitJpeg()
    }

    /** The latest frame, waiting up to [FIRST_FRAME_WAIT_MS] for the first one after the
     * surface is (re)attached rather than handing back nothing. */
    fun awaitJpeg(): ByteArray? {
        var jpeg = latestJpeg()
        var waited = 0L
        while (jpeg == null && waited < FIRST_FRAME_WAIT_MS) {
            Thread.sleep(100); waited += 100
            jpeg = latestJpeg()
        }
        return jpeg
    }

    /** Pull-acquire the latest composited frame and JPEG-encode it. Only work done
     * here happens on the caller's thread — call off the request-handling thread
     * at your target frame interval, never in a tight loop. */
    @Synchronized
    fun latestJpeg(quality: Int = 55): ByteArray? {
        val reader = imageReader?.takeIf { attached } ?: return null
        val image = runCatching { reader.acquireLatestImage() }.getOrNull()
        if (image == null) return lastJpeg // no new frame since last pull — reuse it
        try {
            val bmp = imageToBitmap(image, captureWidth, captureHeight)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bmp.recycle()
            return out.toByteArray().also { lastJpeg = it }
        } catch (e: Exception) {
            Log.w(TAG, "frame encode failed: ${e.message}")
            return lastJpeg
        } finally {
            image.close()
        }
    }

    private fun startCapture(proj: MediaProjection) {
        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.getRealMetrics(metrics)
        val aspect = if (metrics.widthPixels > 0) metrics.heightPixels.toFloat() / metrics.widthPixels else 9f / 16f
        val w = CAPTURE_WIDTH
        val h = (w * aspect).toInt().let { it - (it % 2) }.coerceAtLeast(2)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        // Created WITH the surface (a display made without one reports itself off for good on
        // Android 14, and mirroring never starts), then detached below until someone watches.
        val display = proj.createVirtualDisplay(
            "atvhabridge-screen", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, captureHandler,
        )
        synchronized(this) {
            imageReader = reader
            virtualDisplay = display
            captureWidth = w
            captureHeight = h
            attached = true
            updateSurfaceLocked()
        }
        Log.i(TAG, "virtual display ${w}x$h created")
    }

    /** Attach the ImageReader while anyone watches, detach it otherwise. */
    private fun updateSurfaceLocked() {
        val display = virtualDisplay ?: return
        val want = activeClients > 0 ||
            SystemClock.elapsedRealtime() - lastSnapshotMs < SNAPSHOT_LINGER_MS
        if (want == attached) return
        captureHandler.removeCallbacks(stopIfIdle)
        if (!want && ScreenGrantActivity.canAutoGrant(this)) captureHandler.postDelayed(stopIfIdle, IDLE_STOP_MS)
        if (want) imageReader?.let { r ->
            runCatching { r.acquireLatestImage()?.close() } // drop a frame left from before the detach
            display.surface = r.surface
        } else {
            display.surface = null
            lastJpeg = null
        }
        attached = want
        Log.i(TAG, if (want) "capture attached" else "capture detached (no viewers)")
    }

    private fun imageToBitmap(image: Image, w: Int, h: Int): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPaddingPx = (rowStride - pixelStride * w) / pixelStride
        val padded = Bitmap.createBitmap(w + rowPaddingPx, h, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPaddingPx == 0) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
        padded.recycle()
        return cropped
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.screen_notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_notif_title))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIF_ID = 42
        private const val CAPTURE_WIDTH = 640
        private const val SNAPSHOT_LINGER_MS = 5_000L
        // Long enough that a viewer who looks away (another app on the phone, a video Home shows as art)
        // comes back to a running capture, not a fresh grant: each grant briefly pauses the app on the TV.
        private const val IDLE_STOP_MS = 120_000L
        private const val FIRST_FRAME_WAIT_MS = 2_000L
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        @Volatile var instance: ScreenCaptureService? = null
            private set

        private val captureThread by lazy { HandlerThread("atvhabridge-capture").apply { start() } }
        private val captureHandler by lazy { Handler(captureThread.looper) }

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val intent = Intent(ctx, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        fun isActive(): Boolean = instance != null
    }
}
