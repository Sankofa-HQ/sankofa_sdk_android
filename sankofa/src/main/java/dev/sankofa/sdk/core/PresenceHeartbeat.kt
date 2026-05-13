package dev.sankofa.sdk.core

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.sankofa.sdk.Sankofa
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Live-presence heartbeat — every ~15s while the app is foregrounded
 * the SDK pings `/api/v1/screens/heartbeat` so the dashboard's
 * "X live now" badge reflects who's actually on each screen.
 *
 * Mirrors the web / RN / iOS implementations: ProcessLifecycleOwner
 * gates the timer (started ON_START, stopped ON_STOP). Backgrounded
 * apps stop ticking immediately so we don't paint stale "still live"
 * badges. Resuming foreground sends one immediate heartbeat so the
 * badge updates the instant they return.
 *
 * Server-side TTL handles the rest — a missed heartbeat trims the
 * user from the live set after the window. Presence is decorative;
 * failures are silent.
 */
class PresenceHeartbeat private constructor(
    endpoint: String,
    private val apiKey: String,
) : DefaultLifecycleObserver {

    private val url: URL
    private val handler = Handler(Looper.getMainLooper())
    private val ioPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sankofa-presence").apply { isDaemon = true }
    }
    private val intervalMs: Long = 15_000L
    private val tick: Runnable = object : Runnable {
        override fun run() {
            beat()
            handler.postDelayed(this, intervalMs)
        }
    }
    @Volatile private var running = false

    init {
        val base = endpoint.trimEnd('/')
        url = URL("$base/api/v1/screens/heartbeat")
    }

    fun start() {
        // Subscribe to process-wide lifecycle so we know when the app
        // (the whole app, not a single Activity) goes foreground /
        // background.
        handler.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (running) return
        running = true
        // Prime once so the badge updates immediately on launch /
        // resume — without this we'd wait 15s for the first tick.
        beat()
        handler.postDelayed(tick, intervalMs)
    }

    override fun onStop(owner: LifecycleOwner) {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun beat() {
        val screen = Sankofa.currentScreenName() ?: return
        val distinctId = Sankofa.distinctId() ?: return
        val sessionId = Sankofa.currentSessionId() ?: ""

        val body = JSONObject().apply {
            put("screen", screen)
            put("distinct_id", distinctId)
            if (sessionId.isNotEmpty()) put("session_id", sessionId)
        }.toString()

        ioPool.execute {
            try {
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-api-key", apiKey)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                // Drain + close — server returns 204; we don't care.
                conn.responseCode
                conn.disconnect()
            } catch (_: Throwable) {
                // Presence is decorative — never throw, never retry.
            }
        }
    }

    companion object {
        @Volatile private var instance: PresenceHeartbeat? = null

        /** Boot the heartbeat. Idempotent — repeat calls are no-ops. */
        @JvmStatic
        fun start(endpoint: String, apiKey: String) {
            if (instance != null) return
            val h = PresenceHeartbeat(endpoint, apiKey)
            instance = h
            h.start()
        }
    }
}
