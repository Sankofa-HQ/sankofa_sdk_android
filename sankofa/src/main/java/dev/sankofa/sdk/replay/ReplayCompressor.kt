package dev.sankofa.sdk.replay

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Compresses a [Bitmap] frame into the final wire format:
 *   Bitmap → WebP (LOSSY, quality 60) → GZIP → ByteArray
 *
 * WebP LOSSY gives ~60–80% smaller files than PNG with imperceptible quality loss at quality=60.
 * The subsequent GZIP pass adds another ~10–20% reduction on top.
 *
 * API compatibility:
 *  - API 30+: [Bitmap.CompressFormat.WEBP_LOSSY] (explicit lossy)
 *  - API 21–29: [Bitmap.CompressFormat.WEBP] (legacy – lossy at quality < 100)
 */
internal object ReplayCompressor {

    @Suppress("DEPRECATION")
    fun compress(bitmap: Bitmap): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

        val webpBytes = ByteArrayOutputStream().use { bos ->
            bitmap.compress(format, WEBP_QUALITY, bos)
            bos.toByteArray()
        }

        return ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { gzip -> gzip.write(webpBytes) }
            bos.toByteArray()
        }
    }

    private const val WEBP_QUALITY = 60
}
