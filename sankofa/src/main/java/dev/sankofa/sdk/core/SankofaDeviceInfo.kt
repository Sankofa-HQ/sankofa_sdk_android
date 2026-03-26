package dev.sankofa.sdk.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Collects static device metadata to be attached to every event as default properties.
 */
internal object SankofaDeviceInfo {

    @SuppressLint("HardwareIds")
    fun getProperties(context: Context): Map<String, Any> {
        val metrics = getDisplayMetrics(context)
        return buildMap {
            put("\$os", "Android")
            put("\$os_version", Build.VERSION.RELEASE)
            put("\$sdk_version", Build.VERSION.SDK_INT)
            put("\$manufacturer", Build.MANUFACTURER)
            put("\$brand", Build.BRAND)
            put("\$model", Build.MODEL)
            put("\$device", Build.DEVICE)
            put("\$screen_width", metrics.widthPixels)
            put("\$screen_height", metrics.heightPixels)
            put("\$screen_density", metrics.density)
            put("\$lib", "sankofa-android")
            put("\$lib_version", SANKOFA_SDK_VERSION)
        }
    }

    @Suppress("DEPRECATION")
    private fun getDisplayMetrics(context: Context): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.density = context.resources.displayMetrics.density
        } else {
            wm.defaultDisplay.getMetrics(metrics)
        }
        return metrics
    }

    const val SANKOFA_SDK_VERSION = "1.0.0"
}
