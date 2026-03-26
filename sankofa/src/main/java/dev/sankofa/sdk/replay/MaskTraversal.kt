package dev.sankofa.sdk.replay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText

/**
 * Traverses the View hierarchy and draws privacy masks (solid black rectangles)
 * over sensitive views BEFORE the bitmap is compressed and uploaded.
 *
 * Auto-masks:
 * - Any [EditText] (passwords, credit cards, etc.)
 * - Any [View] where `view.tag == "sankofa_mask"` (developer opt-in masking)
 *
 * Drawing is done via a [Canvas] overlay on the already-captured [Bitmap],
 * so there is zero UI-thread involvement.
 */
internal object MaskTraversal {

    private val maskPaint = Paint().apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.FILL
    }

    /**
     * Applies all required masks to [bitmap] in-place.
     * [rootView] is the DecorView used to translate local view coordinates to global.
     * [maskAllInputs] is driven by the server-side [ReplayConfig].
     */
    fun applyMasks(bitmap: Bitmap, rootView: View, maskAllInputs: Boolean) {
        val canvas = Canvas(bitmap)
        val location = IntArray(2)
        val rootLocation = IntArray(2)
        rootView.getLocationOnScreen(rootLocation)

        traverseAndMask(rootView, canvas, location, rootLocation, maskAllInputs)
    }

    private fun traverseAndMask(
        view: View,
        canvas: Canvas,
        location: IntArray,
        rootLocation: IntArray,
        maskAllInputs: Boolean,
    ) {
        val shouldMask = view.visibility == View.VISIBLE &&
                (
                    (maskAllInputs && view is EditText) ||
                    view.tag == SANKOFA_MASK_TAG
                )

        if (shouldMask) {
            view.getLocationOnScreen(location)
            val left = location[0] - rootLocation[0]
            val top = location[1] - rootLocation[1]
            val rect = Rect(left, top, left + view.width, top + view.height)
            canvas.drawRect(rect, maskPaint)
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                traverseAndMask(view.getChildAt(i), canvas, location, rootLocation, maskAllInputs)
            }
        }
    }

    const val SANKOFA_MASK_TAG = "sankofa_mask"
}
