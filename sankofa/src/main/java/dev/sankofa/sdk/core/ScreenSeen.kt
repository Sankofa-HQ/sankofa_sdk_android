package dev.sankofa.sdk.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Canonical screen-seen emitter — fire-and-forget POST to
 * `/api/v1/screens/seen`. Mirrors the web / RN / iOS implementations:
 * every `Sankofa.screen('Identify')` call hits this so the lexicon +
 * dwell + presence get populated regardless of which Sankofa products
 * the host has enabled.
 *
 * Best-effort by design — failures are silent. The server endpoint is
 * idempotent + TTL-based for presence, so a missed call self-heals on
 * the next call.
 */
object ScreenSeen {
    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sankofa-screen-seen").apply { isDaemon = true }
    }

    @JvmStatic
    fun emit(
        endpoint: String,
        apiKey: String,
        screen: String,
        distinctId: String,
        sessionId: String?,
        properties: Map<String, Any?> = emptyMap(),
    ) {
        if (screen.isEmpty() || distinctId.isEmpty()) return
        val base = endpoint.trimEnd('/')
        val url = URL("$base/api/v1/screens/seen")

        val body = JSONObject().apply {
            put("screen", screen)
            put("distinct_id", distinctId)
            put("ts_ms", System.currentTimeMillis())
            if (!sessionId.isNullOrEmpty()) put("session_id", sessionId)
            if (properties.isNotEmpty()) put("properties", JSONObject(properties))
        }.toString()

        pool.execute {
            try {
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-api-key", apiKey)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.responseCode  // drain
                conn.disconnect()
            } catch (_: Throwable) {
                // Decorative — never throw.
            }
        }
    }
}
