package dev.sankofa.sdk.util

import android.view.View

/**
 * Extension property to easily mask/unmask views in session replays.
 * When set to true, the view will be replaced by a solid black rectangle in the recording.
 */
var View.sankofaMask: Boolean
    get() = tag == "sankofa_mask"
    set(value) {
        tag = if (value) "sankofa_mask" else null
    }
