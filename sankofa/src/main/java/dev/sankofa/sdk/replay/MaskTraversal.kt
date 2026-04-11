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
 * over sensitive views.
 *
 * ## Two-phase design (avoids the capture/mask race)
 *
 * The View hierarchy state can change between when the bitmap is captured
 * (PixelCopy at T0) and when masks would naively be applied (T0 + N ms,
 * after the PixelCopy callback bounces back to the UI thread). During RN
 * navigation animations or fast renders, this race causes EditTexts to
 * appear unmasked because the mask rect is computed against the NEW view
 * position but drawn onto the OLD bitmap.
 *
 * To eliminate the race:
 * 1. [collectMaskRects] runs on the UI thread, on the SAME tick as
 *    `PixelCopy.request(...)`, snapshotting view rects as a list.
 * 2. [drawMaskRects] runs on any thread later, drawing those rects onto
 *    the captured bitmap. Drawing onto a Bitmap is thread-safe.
 *
 * ## Auto-masks
 * - Any [EditText] (passwords, credit cards, names, emails — every text input).
 *   This catches React Native's `ReactEditText` automatically since it
 *   subclasses `androidx.appcompat.widget.AppCompatEditText`.
 * - Any view whose class name contains `ReactEditText` / `RCTTextInput`
 *   (defensive — covers any future RN variant that doesn't subclass EditText).
 * - Any [View] where `view.tag == "sankofa_mask"` (developer opt-in).
 *
 * ## NOT auto-masked
 * - `TextView` and its subclasses (RN's `ReactTextView` for `<Text>` lowers
 *   here). Masking every TextView would black out every label and button
 *   string, which is what high-fidelity replay specifically should NOT do.
 * - `WebView` — auto-masking caught RN-internal helper webviews and produced
 *   false positives. Use `view.tag = "sankofa_mask"` on your own WebViews if
 *   you need privacy on them.
 */
internal object MaskTraversal {

    private val maskPaint = Paint().apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.FILL
    }

    /**
     * Phase 1 — must run on the UI thread, on the SAME tick as the bitmap
     * capture, so the rects we record match the view positions baked into
     * the bitmap.
     */
    fun collectMaskRects(rootView: View, maskAllInputs: Boolean): List<Rect> {
        val out = ArrayList<Rect>(8)
        val rootLocation = IntArray(2)
        rootView.getLocationOnScreen(rootLocation)
        val location = IntArray(2)
        traverse(rootView, out, location, rootLocation, maskAllInputs)
        return out
    }

    /**
     * Phase 2 — draws the rects collected in phase 1 onto the captured
     * bitmap. Safe to run on any thread.
     */
    fun drawMaskRects(bitmap: Bitmap, rects: List<Rect>) {
        if (rects.isEmpty()) return
        val canvas = Canvas(bitmap)
        val w = bitmap.width
        val h = bitmap.height
        for (r in rects) {
            // Defensive clip: drop rects that are entirely outside the bitmap
            // (e.g. because the view was off-screen at the time of capture).
            if (r.right <= 0 || r.bottom <= 0 || r.left >= w || r.top >= h) continue
            if (r.width() <= 0 || r.height() <= 0) continue
            canvas.drawRect(r, maskPaint)
        }
    }

    private fun traverse(
        view: View,
        out: MutableList<Rect>,
        location: IntArray,
        rootLocation: IntArray,
        maskAllInputs: Boolean,
    ) {
        if (view.visibility != View.VISIBLE) return

        val shouldMask =
            (maskAllInputs && isTextInputLike(view)) ||
            view.tag == SANKOFA_MASK_TAG

        if (shouldMask && view.width > 0 && view.height > 0) {
            view.getLocationOnScreen(location)
            val left = location[0] - rootLocation[0]
            val top = location[1] - rootLocation[1]
            // Skip "ghost" views that haven't been laid out yet — their
            // getLocationOnScreen returns (0,0) before the first layout pass,
            // which would draw a useless mask in the corner.
            if (left == 0 && top == 0 && !view.isAttachedToWindow) return
            out.add(Rect(left, top, left + view.width, top + view.height))
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                traverse(view.getChildAt(i), out, location, rootLocation, maskAllInputs)
            }
        }
    }

    /**
     * Detects text-input views, including React Native's wrappers.
     *
     * RN's `<TextInput>` lowers to `com.facebook.react.views.textinput.ReactEditText`
     * which subclasses `androidx.appcompat.widget.AppCompatEditText` → `EditText`,
     * so the `is EditText` check normally catches it. The class-name fallback
     * is defensive insurance for any future RN variant that stops subclassing
     * EditText.
     *
     * IMPORTANT: We deliberately do NOT match `TextView` here. Every RN `<Text>`
     * lowers to `ReactTextView` (a TextView subclass), so masking by `is TextView`
     * would black out every label, button caption, and UI string in the app.
     * Likewise we don't trust `TextView.isCursorVisible()` because its default
     * is `true` for non-editable TextViews — that bug is what caused the
     * over-masking regression.
     */
    private fun isTextInputLike(view: View): Boolean {
        if (view is EditText) return true
        val name = view.javaClass.name
        return name.contains("ReactEditText") ||
               name.contains("RCTTextInput") ||
               name.contains("ReactTextInput")
    }

    const val SANKOFA_MASK_TAG = "sankofa_mask"
}
