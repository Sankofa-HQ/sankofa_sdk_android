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

        val listener = ViewTreeObserver.OnDrawListener { onDraw(activity) }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            captureWithPixelCopy(activity, bitmap)
        } else {
            captureWithCanvas(activity, bitmap)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun captureWithPixelCopy(activity: Activity, bitmap: Bitmap) {
        val captureTimestamp = System.currentTimeMillis()
        PixelCopy.request(
            activity.window,
            bitmap,
            { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    processCapture(activity, bitmap, captureTimestamp)
                } else {
                    logger.debug("⚠️ PixelCopy failed ($copyResult) – dropping frame to prevent ANR")
                    bitmapPool.release(bitmap)
                    isCapturing.set(false)
                }
            },
            pixelCopyHandler,
        )
    }

    private fun captureWithCanvas(activity: Activity, bitmap: Bitmap) {
        val decor = decorView
        if (decor == null) {
            bitmapPool.release(bitmap)
            isCapturing.set(false)
            return
        }
        try {
            val canvas = Canvas(bitmap)
            decor.draw(canvas)
            processCapture(activity, bitmap)
        } catch (e: Exception) {
            logger.error("❌ Canvas capture failed", e)
            bitmapPool.release(bitmap)
            isCapturing.set(false)
        }
    }

    private fun processCapture(activity: Activity, bitmap: Bitmap, captureTimestamp: Long = System.currentTimeMillis()) {
        // Step 1: Traverse and apply masks on the Main Thread (UI MUST be touched on Main Thread)
        activity.runOnUiThread {
            try {
                decorView?.let { root ->
                    MaskTraversal.applyMasks(bitmap, root, maskAllInputs)
                }
            } catch (e: Exception) {
                logger.error("❌ Mask compilation failed", e)
            }
            
            // 🚀 Extract snapshot metadata safely on the Main thread before bouncing to IO
            var scrollOffsetY = 0
            decorView?.let { root ->
                findActiveScrollView(root)?.let { scrollOffsetY = it.scrollY }
            }
            val screen = dev.sankofa.sdk.Sankofa.getCurrentScreenName()

            // Step 2: Compress and upload purely in the background
            scope.launch {
                try {
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
    }
}
