package dev.sankofa.sdk.heatmap

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.view.PixelCopy
import android.view.View
import dev.sankofa.sdk.replay.MaskTraversal
import dev.sankofa.sdk.util.SankofaLogger
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Dedicated heatmap-background capture path for Android — independent
 * of the session-replay frame stream.
 *
 * ## Why this exists
 * `ReplayRecorder` captures a frame on every `OnDrawListener` tick, so
 * the very first frame after a screen transition often catches the
 * layout half-painted (async glide loads, RecyclerView item-bind in
 * progress, view animator mid-flight). That partial bitmap then
 * ended up locked in as the heatmap background. Result: heatmap dots
 * floating over a blank/half-loaded view.
 *
 * This snapshotter fires ONCE per `(screen, app_version, viewport
 * bucket)` per process lifetime, AFTER a settle delay measured from
 * the most recent `Sankofa.screen(...)` call, and uploads directly to
 * `/api/heatmaps/snapshot` (capture_source = 'dedicated' on the
 * server side, which out-ranks replay-derived rows).
 *
 * ## Performance contract
 * - All scheduling + encoding + upload runs on a dedicated background
 *   `HandlerThread`. The host's main thread is never blocked.
 * - The actual `PixelCopy.request(...)` call is itself async — it
 *   does the GPU readback on its own worker.
 * - PNG encoding runs on a worker thread (`encodePool`).
 * - One capture per screen-bucket per session. Re-tagging the same
 *   screen does NOT re-capture; the backend's sliding window absorbs
 *   any updates the next time the app is launched.
 * - Cancellable: if the user navigates away (a new screen tag comes
 *   in) during the settle delay, the pending capture is discarded.
 */
internal class HeatmapSnapshotter(
    private val apiKey: String,
    private val endpoint: String,
    private val appVersion: String,
    private val maskAllInputs: Boolean,
    private val logger: SankofaLogger,
) {

    /** 1.5s settle delay — same value used by the iOS counterpart. */
    private val stabilityDelayMs = 1_500L

    private val scheduler = HandlerThread("sankofa-heatmap-snap").also { it.start() }
    private val handler = Handler(scheduler.looper)

    // Separate pool for PNG encoding so we don't tie up the scheduler
    // looper (which also runs PixelCopy callbacks).
    private val encodePool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sankofa-heatmap-encode").apply { isDaemon = true }
    }

    private val captured = ConcurrentHashMap.newKeySet<String>()
    private var pendingToken: UUID? = null
    private var activityRef: WeakReference<Activity> = WeakReference(null)

    /** Called from `SankofaLifecycleObserver.onActivityResumed`. */
    fun setActivity(activity: Activity?) {
        activityRef = WeakReference(activity)
    }

    /**
     * Called from `Sankofa.screen(...)`. Posts a delayed task on the
     * scheduler thread — returns immediately to the caller. No UI-thread
     * work happens here.
     */
    fun scheduleCapture(screen: String) {
        if (screen.isEmpty()) return
        val token = UUID.randomUUID()
        pendingToken = token
        handler.postDelayed({
            if (pendingToken != token) return@postDelayed
            runCaptureIfNeeded(screen)
        }, stabilityDelayMs)
    }

    private fun runCaptureIfNeeded(screen: String) {
        val activity = activityRef.get() ?: return
        val decor = activity.window.decorView
        val w = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val h = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        if (w <= 0 || h <= 0) return

        val fingerprint = "$screen|$appVersion|${w / 60}x${h / 60}"
        if (!captured.add(fingerprint)) return

        // Collect masks on the UI thread (required — view positions
        // must be read on the same tick as the capture) then bounce
        // immediately back off-main for the bitmap allocation +
        // PixelCopy request.
        activity.runOnUiThread {
            val masks = try {
                MaskTraversal.collectMaskRects(decor, maskAllInputs)
            } catch (t: Throwable) {
                logger.warn("Heatmap mask collection failed: ${t.message}")
                emptyList<Rect>()
            }
            // Bitmap allocation can be expensive; hop back to scheduler
            // thread before allocating + requesting PixelCopy. Bitmap
            // itself is mutable and thread-safe to draw into.
            handler.post {
                captureWithPixelCopy(activity, decor, w, h, masks, screen)
            }
        }
    }

    private fun captureWithPixelCopy(
        activity: Activity,
        decor: View,
        width: Int,
        height: Int,
        masks: List<Rect>,
        screen: String,
    ) {
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            logger.warn("Heatmap snapshot OOM allocating ${width}x${height} bitmap")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                PixelCopy.request(
                    activity.window,
                    bitmap,
                    { result ->
                        if (result == PixelCopy.SUCCESS) {
                            applyMasksAndUpload(bitmap, masks, screen, width, height)
                        } else {
                            logger.warn("Heatmap PixelCopy failed (code=$result)")
                            bitmap.recycle()
                        }
                    },
                    handler,
                )
            } catch (t: Throwable) {
                logger.warn("Heatmap PixelCopy threw: ${t.message}")
                bitmap.recycle()
            }
        } else {
            // API 21-25 fallback: draw on UI thread once, then encode
            // off-main.
            activity.runOnUiThread {
                try {
                    val canvas = Canvas(bitmap)
                    decor.draw(canvas)
                    handler.post {
                        applyMasksAndUpload(bitmap, masks, screen, width, height)
                    }
                } catch (t: Throwable) {
                    logger.warn("Heatmap canvas capture failed: ${t.message}")
                    bitmap.recycle()
                }
            }
        }
    }

    private fun applyMasksAndUpload(
        bitmap: Bitmap,
        masks: List<Rect>,
        screen: String,
        width: Int,
        height: Int,
    ) {
        encodePool.execute {
            try {
                if (masks.isNotEmpty()) {
                    MaskTraversal.drawMaskRects(bitmap, masks)
                }
                val baos = ByteArrayOutputStream(64 * 1024)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                bitmap.recycle()
                val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                upload(screen, width, height, base64)
            } catch (t: Throwable) {
                logger.warn("Heatmap encode/upload failed: ${t.message}")
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun upload(screen: String, width: Int, height: Int, imageBase64: String) {
        val base = endpoint.trimEnd('/')
        val url = try {
            URL("$base/api/heatmaps/snapshot")
        } catch (t: Throwable) {
            return
        }

        val body = JSONObject().apply {
            put("screen_name", screen)
            put("app_version", appVersion)
            put("os", "android")
            put("device_width", width)
            put("device_height", height)
            put("scroll_offset_y", 0)
            put("image_base64", imageBase64)
        }.toString()

        try {
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            if (code >= 400) {
                logger.warn("Heatmap snapshot rejected ($code) for screen '$screen'")
            } else {
                logger.debug("📸 Heatmap snapshot accepted for screen '$screen' (status $code)")
            }
        } catch (t: Throwable) {
            logger.warn("Heatmap snapshot upload threw: ${t.message}")
        }
    }
}
