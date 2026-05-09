package dev.sankofa.sdk

/**
 * Handle returned by [Sankofa.tagScrollContainer] — used by the host to
 * unregister the scroll-offset provider when the scrollable leaves
 * composition or scope.
 *
 * Removing the handle multiple times is safe (idempotent).
 */
class ScrollContainerHandle internal constructor(
    private val onRemove: () -> Unit,
) {
    @Volatile
    private var removed = false

    /**
     * Stop forwarding scroll offsets to the heatmap pipeline.
     *
     * Typically called from a Compose `DisposableEffect`'s `onDispose`
     * block, or a Fragment's `onDestroyView`.  Idempotent.
     */
    fun remove() {
        if (removed) return
        removed = true
        onRemove()
    }
}
