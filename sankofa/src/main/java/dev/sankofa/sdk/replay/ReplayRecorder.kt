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
    private val scope = CoroutineScope(Dispatchers.IO)

    // HandlerThread for PixelCopy callbacks (must not be the Main thread)
    private val pixelCopyThread = HandlerThread("sankofa-pixelcopy").also { it.start() }
    private val pixelCopyHandler = Handler(pixelCopyThread.looper)

    private var decorView: View? = null
    private var drawListener: ViewTreeObserver.OnDrawListener? = null

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

        logger.debug("🎬 ReplayRecorder started for ${activity.localClassName}")
    }

    fun stopRecording() {
        val decor = decorView ?: return
        drawListener?.let {
            if (decor.viewTreeObserver.isAlive) {
                decor.viewTreeObserver.removeOnDrawListener(it)
            }
        }
        drawListener = null
        decorView = null
        logger.debug("🛑 ReplayRecorder stopped")
    }

    private fun onDraw(activity: Activity) {
        // Guard: skip if a capture is already in flight
        if (!isCapturing.compareAndSet(false, true)) return

        val bitmap = bitmapPool.acquire()
        if (bitmap == null) {
            isCapturing.set(false)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            captureWithPixelCopy(activity, bitmap)
        } else {
            captureWithCanvas(bitmap)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun captureWithPixelCopy(activity: Activity, bitmap: Bitmap) {
        PixelCopy.request(
            activity.window,
            bitmap,
            { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    processCapture(bitmap)
                } else {
                    logger.debug("⚠️ PixelCopy failed ($copyResult) – falling back to canvas")
                    captureWithCanvas(bitmap)
                }
            },
            pixelCopyHandler,
        )
    }

    private fun captureWithCanvas(bitmap: Bitmap) {
        val decor = decorView
        if (decor == null) {
            bitmapPool.release(bitmap)
            isCapturing.set(false)
            return
        }
        try {
            val canvas = Canvas(bitmap)
            decor.draw(canvas)
            processCapture(bitmap)
        } catch (e: Exception) {
            logger.error("❌ Canvas capture failed", e)
            bitmapPool.release(bitmap)
            isCapturing.set(false)
        }
    }

    private fun processCapture(bitmap: Bitmap) {
        scope.launch {
            try {
                // Apply privacy masks (EditText + sankofa_mask tagged views)
                decorView?.let { root ->
                    MaskTraversal.applyMasks(bitmap, root, maskAllInputs)
                }

                // Compress and hand off to the uploader
                val compressed = ReplayCompressor.compress(bitmap)
                uploader.enqueueFrame(compressed)
            } catch (e: Exception) {
                logger.error("❌ Frame processing failed", e)
            } finally {
                bitmapPool.release(bitmap)
                isCapturing.set(false)
            }
        }
    }
}
