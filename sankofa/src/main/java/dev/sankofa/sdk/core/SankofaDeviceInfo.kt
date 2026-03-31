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
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        
        return buildMap {
            put("\$os", "Android")
            put("\$os_version", Build.VERSION.RELEASE)
            put("\$sdk_version", Build.VERSION.SDK_INT)
            put("\$manufacturer", Build.MANUFACTURER)
            put("\$brand", Build.BRAND)
            put("\$model", Build.MODEL)
            put("\$device_model", Build.MODEL)
            put("\$device_manufacturer", Build.MANUFACTURER)
            put("\$is_simulator", isSimulator())
            
            put("\$screen_width", metrics.widthPixels)
            put("\$screen_height", metrics.heightPixels)
            put("\$screen_dpi", (metrics.density * 160).toInt())
            
            put("\$app_version", packageInfo.versionName ?: "unknown")
            put("\$app_version_string", packageInfo.versionName ?: "unknown")
            put("\$app_build_number", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode.toString() else packageInfo.versionCode.toString())

            // Simple WiFi check (requires ACCESS_NETWORK_STATE)
            put("\$wifi", isWifiConnected(context))
            
            put("\$lib", "sankofa-android")
            put("\$lib_version", SANKOFA_SDK_VERSION)
        }
    }

    private fun isSimulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT)
    }

    private fun isWifiConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(network)
                capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.type == android.net.ConnectivityManager.TYPE_WIFI
            }
        } catch (e: Exception) {
            false
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
