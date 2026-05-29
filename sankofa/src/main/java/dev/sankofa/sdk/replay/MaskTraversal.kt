package dev.sankofa.sdk.replay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.VideoView

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
 * - "Opaque-content" surfaces whose pixels we can't reason about and which
 *   routinely render sensitive content: [WebView] (login / payment / 3DS
 *   forms), [SurfaceView] / [TextureView] (camera previews, video, GL), and
 *   [VideoView]. Privacy beats fidelity here — a black box is the safe default.
 * - Any [View] where `view.tag == "sankofa_mask"` (developer opt-in).
 * - Jetpack Compose nodes tagged with `Modifier.sankofaMask()`. Compose draws
 *   into a single `AndroidComposeView` with no per-field child View, so the
 *   View walk below can't see Compose `TextField`s — the modifier reports its
 *   window bounds into [ComposeMaskRegistry] instead, merged in by
 *   [collectMaskRects].
 *
 * ## NOT auto-masked
 * - `TextView` and its subclasses (RN's `ReactTextView` for `<Text>` lowers
 *   here). Masking every TextView would black out every label and button
 *   string, which is what high-fidelity replay specifically should NOT do.
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

        // Merge Compose-reported masks. These are window-relative bounds, which
        // align with the View-tree rects above because `rootView` is the decor
        // view (the window's origin). We always honour them — a Compose field
        // is tagged with Modifier.sankofaMask() precisely because it's sensitive,
        // independent of the maskAllInputs toggle.
        val composeRects = ComposeMaskRegistry.snapshot()
        if (composeRects.isNotEmpty()) {
            for (r in composeRects) {
                if (r.width() > 0 && r.height() > 0) out.add(r)
            }
        }
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
            (maskAllInputs && (isTextInputLike(view) || isOpaqueSensitiveSurface(view))) ||
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

    /**
     * Views whose pixels we can't introspect and which commonly carry
     * sensitive content. We mask them wholesale because the alternative —
     * shipping a screenshot of a payment WebView or a camera preview — is a
     * privacy violation, and there's no reliable way to redact only part of
     * an opaque surface. Hosts with a known-safe WebView can carve it back in
     * later via an allow-list; the safe default is to black it out.
     */
    private fun isOpaqueSensitiveSurface(view: View): Boolean {
        if (view is WebView) return true
        if (view is SurfaceView) return true
        if (view is TextureView) return true
        if (view is VideoView) return true
        // Custom subclasses that don't extend the framework types (e.g. some
        // cross-platform WebView wrappers) still announce themselves by name.
        return view.javaClass.name.contains("WebView")
    }

    const val SANKOFA_MASK_TAG = "sankofa_mask"
}
