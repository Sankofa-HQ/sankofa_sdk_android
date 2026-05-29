package dev.sankofa.sdk.replay

import android.graphics.Rect
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the current on-screen bounds of every Jetpack Compose node tagged with
 * `Modifier.sankofaMask()`.
 *
 * Compose renders into a single `AndroidComposeView`, so the classic View-tree
 * walk in [MaskTraversal] can never see an individual Compose `TextField`. The
 * modifier instead reports its window-relative bounds here on every layout pass
 * and removes them when the node leaves composition. [MaskTraversal.collectMaskRects]
 * merges the snapshot so Compose inputs are masked on the same frame as classic
 * views.
 *
 * Keyed by the Modifier node instance (identity), so multiple masked composables
 * coexist and a recomposition that moves a field updates its rect in place.
 */
internal object ComposeMaskRegistry {

    private val rects = ConcurrentHashMap<Any, Rect>()

    /** Report/refresh the window-relative bounds for [token] (the Modifier node). */
    fun update(token: Any, rect: Rect) {
        rects[token] = rect
    }

    /** Drop [token]'s mask — called when the composable leaves composition. */
    fun remove(token: Any) {
        rects.remove(token)
    }

    /** Snapshot of all active Compose mask rects. Safe to call from the capture thread. */
    fun snapshot(): List<Rect> = if (rects.isEmpty()) emptyList() else rects.values.toList()
}
