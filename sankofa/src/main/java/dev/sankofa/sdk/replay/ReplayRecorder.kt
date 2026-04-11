package dev.sankofa.sdk.replay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import dev.sankofa.sdk.util.SankofaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Session Replay capture engine.
 *
 * Attach/detach via [startRecording]/[stopRecording] from the
 * [SankofaLifecycleObserver.activityCallbacks].
 *
 * ## Capture strategy
 * We attach a [ViewTreeObserver.OnDrawListener] to the DecorView. This fires
 * *only when the screen changes*, so we never waste CPU capturing idle frames.
 *
 * - **API 26+**: [PixelCopy] captures hardware-accelerated layers correctly
 *   without freezing the Main thread.
 * - **API 21–25 fallback**: `view.draw(canvas)` on a memory-backed Canvas.
 *
 * ## Memory safety
 * A [BitmapPool] is used so the same 2 Bitmap instances are reused every frame.
 * An [AtomicBoolean] guard ensures we never start a second capture before the
 * first one finishes, preventing OOM from bitmap accumulation.
 */
internal class ReplayRecorder(
    private val logger: SankofaLogger,
    private val bitmapPool: BitmapPool,
    private val maskAllInputs: Boolean,
    private val uploader: ReplayUploader,
) {
    private val isCapturing = AtomicBoolean(false)
    private var lastCaptureTimeMs = 0L
    private val scope = CoroutineScope(Dispatchers.IO)

    // HandlerThread for PixelCopy callbacks (must not be the Main thread)
    private val pixelCopyThread = HandlerThread("sankofa-pixelcopy").also { it.start() }
    private val pixelCopyHandler = Handler(pixelCopyThread.looper)

    private var decorView: View? = null
    private var drawListener: ViewTreeObserver.OnDrawListener? = null
    private var originalWindowCallback: android.view.Window.Callback? = null

    // ── Double-tap recognition ────────────────────────────────────────────
    // When two ACTION_DOWN events fire within DOUBLE_TAP_INTERVAL_MS and
    // DOUBLE_TAP_RADIUS_PX of each other, we emit an additional touch event
    // with type = 4 (rrweb dblclick) so the dashboard can render a "2×"
    // marker overlay distinct from regular taps.
    private val doubleTapIntervalMs = 350L
    private val doubleTapRadiusPx = 25
    private var lastTapAt = 0L
    private var lastTapX = -9999
    private var lastTapY = -9999

    fun startRecording(activity: Activity) {
        stopRecording() // detach from any previous activity first

        val decor = activity.window.decorView
        val w = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val h = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels

        bitmapPool.configure(w, h)
        decorView = decor

        // We defer the actual capture to `decor.post { onDraw(...) }` so it
        // runs AFTER the current draw cycle completes. OnDrawListener fires
        // *before* the surface is committed, so capturing inline can yield
        // a half-painted/empty bitmap during navigation transitions. Posting
        // to the next message loop iteration guarantees the surface has the
        // committed pixels of the frame this listener was triggered for.
        val listener = ViewTreeObserver.OnDrawListener {
            decor.post { onDraw(activity) }
        }
        decor.viewTreeObserver.addOnDrawListener(listener)
        drawListener = listener

        // Intercept Touch Events for Session Replay 'Fake Cursor'
        val existingCallback = activity.window.callback
        originalWindowCallback = existingCallback
        activity.window.callback = object : android.view.Window.Callback by existingCallback {
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                if (event.action == android.view.MotionEvent.ACTION_DOWN || event.action == android.view.MotionEvent.ACTION_UP) {
                    val actionType = if (event.action == android.view.MotionEvent.ACTION_DOWN) 1 else 0

                    // 🚀 Infinite Scroll Support: Find active scroll offset
                    var scrollOffsetY = 0
                    findActiveScrollView(decor)?.let {
                        scrollOffsetY = it.scrollY
                    }

                    val absY = event.y.toInt() + scrollOffsetY
                    val screen = dev.sankofa.sdk.Sankofa.getCurrentScreenName()
                    val now = System.currentTimeMillis()
                    val ex = event.x.toInt()
                    val ey = event.y.toInt()

                    uploader.enqueueTouchEvent(
                        x = ex,
                        y = ey,
                        absoluteY = absY,
                        scrollOffsetY = scrollOffsetY,
                        screen = screen,
                        timestamp = now,
                        type = actionType
                    )

                    // ── Double-tap recognition (ACTION_DOWN only) ──────────
                    if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                        val dt = now - lastTapAt
                        val dx = ex - lastTapX
                        val dy = ey - lastTapY
                        val isDouble =
                            lastTapAt > 0 &&
                            dt < doubleTapIntervalMs &&
                            dx * dx + dy * dy <
                                doubleTapRadiusPx * doubleTapRadiusPx

                        if (isDouble) {
                            uploader.enqueueTouchEvent(
                                x = ex,
                                y = ey,
                                absoluteY = absY,
                                scrollOffsetY = scrollOffsetY,
                                screen = screen,
                                timestamp = now,
                                type = 4 // rrweb dblclick
                            )
                            // Reset so a third tap doesn't fire another double.
                            lastTapAt = 0L
                            lastTapX = -9999
                            lastTapY = -9999
                        } else {
                            lastTapAt = now
                            lastTapX = ex
                            lastTapY = ey
                        }
                    }
                }
                return existingCallback.dispatchTouchEvent(event)
            }
        }

        logger.debug("🎬 ReplayRecorder started for ${activity.localClassName}")
    }

    private fun findActiveScrollView(view: View): View? {
        val name = view.javaClass.name
        if (view is android.widget.ScrollView || view is android.widget.AbsListView || 
            name.contains("RecyclerView") || name.contains("ScrollView") || name.contains("WebView")) {
            if (view.isShown) return view
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findActiveScrollView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    fun stopRecording() {
        val decor = decorView ?: return
        drawListener?.let {
            if (decor.viewTreeObserver.isAlive) {
                decor.viewTreeObserver.removeOnDrawListener(it)
            }
        }
        decorView?.context?.let { ctx ->
            if (ctx is Activity && originalWindowCallback != null) {
                ctx.window.callback = originalWindowCallback
            }
        }
        originalWindowCallback = null

        drawListener = null
        decorView = null
        logger.debug("🛑 ReplayRecorder stopped")
    }

    /**
     * Momentarily triggers high-fidelity mode.
     * In the current implementation, this forces an immediate one-shot capture.
     */
    fun triggerHighFidelityMode(durationMs: Long) {
        val decor = decorView ?: return
        val ctx = decor.context
        if (ctx is Activity) {
            ctx.runOnUiThread {
                onDraw(ctx, forced = true)
            }
        }
    }

    fun destroy() {
        stopRecording()
        scope.cancel()
        pixelCopyThread.quitSafely()
    }

    private fun onDraw(activity: Activity, forced: Boolean = false) {
        // Guard: throttle to 2 frames per second (500ms)
        val now = System.currentTimeMillis()
        if (!forced && now - lastCaptureTimeMs < 500) return

        // Guard: skip if a capture is already in flight
        if (!isCapturing.compareAndSet(false, true)) return
        lastCaptureTimeMs = now

        val decor = decorView ?: run {
            isCapturing.set(false)
            return
        }
        val w = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val h = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        bitmapPool.configure(w, h)

        val bitmap = bitmapPool.acquire()
        if (bitmap == null) {
            isCapturing.set(false)
            return
        }

        // 🔒 Mask snapshot — collected on the SAME UI-thread tick as the
        // capture call below, so the rects are guaranteed to align with
        // the view positions baked into the bitmap. This eliminates the
        // race where a delayed mask pass would draw onto stale geometry
        // (a bug that left RN TextInputs visible during screen animations).
        val maskRects = try {
            MaskTraversal.collectMaskRects(decor, maskAllInputs)
        } catch (e: Exception) {
            logger.error("❌ Mask collection failed", e)
            emptyList()
        }

        // 🚀 Capture scroll + screen metadata in the same UI tick.
        var scrollOffsetY = 0
        findActiveScrollView(decor)?.let { scrollOffsetY = it.scrollY }
        val screen = dev.sankofa.sdk.Sankofa.getCurrentScreenName()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            captureWithPixelCopy(activity, bitmap, maskRects, scrollOffsetY, screen)
        } else {
            captureWithCanvas(activity, bitmap, maskRects, scrollOffsetY, screen)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun captureWithPixelCopy(
        activity: Activity,
        bitmap: Bitmap,
        maskRects: List<android.graphics.Rect>,
        scrollOffsetY: Int,
        screen: String,
    ) {
        val captureTimestamp = System.currentTimeMillis()
        PixelCopy.request(
            activity.window,
            bitmap,
            { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    processCapture(bitmap, maskRects, scrollOffsetY, screen, captureTimestamp)
                } else {
                    logger.debug("⚠️ PixelCopy failed ($copyResult) – dropping frame to prevent ANR")
                    bitmapPool.release(bitmap)
                    isCapturing.set(false)
                }
            },
            pixelCopyHandler,
        )
    }

    private fun captureWithCanvas(
        activity: Activity,
        bitmap: Bitmap,
        maskRects: List<android.graphics.Rect>,
        scrollOffsetY: Int,
        screen: String,
    ) {
        val decor = decorView
        if (decor == null) {
            bitmapPool.release(bitmap)
            isCapturing.set(false)
            return
        }
        try {
            val canvas = Canvas(bitmap)
            decor.draw(canvas)
            processCapture(bitmap, maskRects, scrollOffsetY, screen)
        } catch (e: Exception) {
            logger.error("❌ Canvas capture failed", e)
            bitmapPool.release(bitmap)
            isCapturing.set(false)
        }
    }

    private fun processCapture(
        bitmap: Bitmap,
        maskRects: List<android.graphics.Rect>,
        scrollOffsetY: Int,
        screen: String,
        captureTimestamp: Long = System.currentTimeMillis(),
    ) {
        // Step 1: Apply pre-collected masks. Drawing onto a Bitmap is
        // thread-safe, so we stay on the IO scope and skip the bounce
        // back to the UI thread. This is what eliminates the mask race.
        scope.launch {
            try {
                // Drop blank/uniform frames before doing any work.
                // These appear during RN navigation transitions when the
                // new screen container is laid out but its children haven't
                // been attached yet — PixelCopy succeeds but returns the
                // window background, which the dashboard then renders as a
                // "massive white screen".
                if (isBlankFrame(bitmap)) {
                    logger.debug("⏭ Skipping blank/uniform frame (likely mid-transition)")
                    return@launch
                }
                MaskTraversal.drawMaskRects(bitmap, maskRects)
                val compressed = ReplayCompressor.compress(bitmap)
                uploader.enqueueFrame(compressed, captureTimestamp, bitmap.width, bitmap.height, scrollOffsetY, screen)
            } catch (e: Exception) {
                logger.error("❌ Frame processing failed", e)
            } finally {
                bitmapPool.release(bitmap)
                isCapturing.set(false)
            }
        }
    }

    /**
     * Detects "empty" captures: bitmaps where every sampled pixel is the
     * same color (all transparent, all white, all black, etc.).
     *
     * Why sampling instead of a full scan: a 1080×2400 bitmap has 2.6M pixels.
     * A 9-point sample covers the corners + edges + center, which is enough
     * to catch a uniformly-colored frame in O(1) without scanning the whole
     * thing on the IO thread. False negatives (frames with content but
     * uniform sample points) are essentially impossible with a 9-point grid;
     * false positives (1×1 solid-color screens) are not real apps.
     */
    private fun isBlankFrame(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return true
        val w = bitmap.width
        val h = bitmap.height
        if (w < 4 || h < 4) return true

        // 9-point grid: corners + edges + center (with a small inset to
        // skip status-bar / nav-bar artifacts that are uniform by design).
        val insetX = (w * 0.1f).toInt().coerceAtLeast(1)
        val insetY = (h * 0.15f).toInt().coerceAtLeast(1)
        val samples = intArrayOf(
            bitmap.getPixel(insetX, insetY),
            bitmap.getPixel(w / 2, insetY),
            bitmap.getPixel(w - insetX - 1, insetY),
            bitmap.getPixel(insetX, h / 2),
            bitmap.getPixel(w / 2, h / 2),
            bitmap.getPixel(w - insetX - 1, h / 2),
            bitmap.getPixel(insetX, h - insetY - 1),
            bitmap.getPixel(w / 2, h - insetY - 1),
            bitmap.getPixel(w - insetX - 1, h - insetY - 1),
        )
        val first = samples[0]
        for (i in 1 until samples.size) {
            if (samples[i] != first) return false
        }
        return true
    }
}
