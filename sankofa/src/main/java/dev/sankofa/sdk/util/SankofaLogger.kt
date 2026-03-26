package dev.sankofa.sdk.util

import android.util.Log

/**
 * Lightweight logger that only emits output when [debug] mode is enabled.
 * Uses Android's [Log] under the hood with a consistent tag.
 */
internal class SankofaLogger(private val debug: Boolean = false) {

    fun debug(message: String) {
        if (debug) Log.d(TAG, message)
    }

    fun warn(message: String) {
        if (debug) Log.w(TAG, message)
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (debug) Log.e(TAG, message, throwable)
    }

    companion object {
        private const val TAG = "Sankofa"
    }
}
