package dev.sankofa.sdk.replay

import android.graphics.Bitmap
import android.graphics.Point
import dev.sankofa.sdk.util.SankofaLogger

/**
 * A fixed-size pool of reusable [Bitmap] instances to eliminate per-frame GC pressure.
 *
 * **The CTO mandate**: Never allocate a new Bitmap every frame or the GC will destroy
 * app performance. This pool keeps [POOL_SIZE] bitmaps alive and reuses them in rotation.
 *
 * Usage:
 * ```kotlin
 * val bitmap = pool.acquire() ?: return  // pool might be configuring
 * try {
 *     // render into bitmap...
 * } finally {
 *     pool.release(bitmap)
 * }
 * ```
 */
internal class BitmapPool(
    private val logger: SankofaLogger,
    private val poolSize: Int = POOL_SIZE,
) {
    private val pool = ArrayDeque<Bitmap>(poolSize)
    private var bitmapWidth = 0
    private var bitmapHeight = 0

    /**
     * Must be called whenever the Activity window size is known.
     * If dimensions change, all existing bitmaps are recycled and recreated.
     */
    @Synchronized
    fun configure(width: Int, height: Int) {
        if (width == bitmapWidth && height == bitmapHeight && pool.size == poolSize) return

        // Recycle old bitmaps
        pool.forEach { if (!it.isRecycled) it.recycle() }
        pool.clear()

        bitmapWidth = width
        bitmapHeight = height

        repeat(poolSize) {
            pool.addLast(
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            )
        }
        logger.debug("🖼 BitmapPool configured: ${width}×${height}, size=$poolSize")
    }

    /**
     * Claims a bitmap from the pool. Returns null if the pool is unconfigured or exhausted.
     * The caller is responsible for calling [release] when done.
     */
    @Synchronized
    fun acquire(): Bitmap? {
        if (bitmapWidth == 0 || pool.isEmpty()) return null
        return pool.removeFirst()
    }

    /**
     * Returns a bitmap to the pool for reuse. The bitmap is NOT recycled.
     */
    @Synchronized
    fun release(bitmap: Bitmap) {
        if (pool.size < poolSize) {
            pool.addLast(bitmap)
        } else {
            // Pool is full (shouldn't happen in normal usage), recycle the overflow
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** Recycles all pooled bitmaps. Call when the SDK is shutting down. */
    @Synchronized
    fun destroy() {
        pool.forEach { if (!it.isRecycled) it.recycle() }
        pool.clear()
        bitmapWidth = 0
        bitmapHeight = 0
    }

    companion object {
        private const val POOL_SIZE = 2
    }
}
