package dev.sankofa.sdk.catchmod

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import dev.sankofa.sdk.Sankofa
import dev.sankofa.sdk.core.SankofaModuleName
import dev.sankofa.sdk.core.SankofaModuleRegistry
import dev.sankofa.sdk.core.SankofaPluggableModule
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sankofa Catch — error tracking on Android.
 *
 * Usage:
 * ```kotlin
 * Sankofa.init(context = this, apiKey = "sk_live_...")
 * SankofaCatch.init(applicationContext)
 *
 * // anywhere:
 * try { risky() } catch (e: Throwable) {
 *     SankofaCatch.captureException(e)
 * }
 * ```
 *
 * M1 surface:
 *   - captureException / captureMessage / addBreadcrumb / setUser / setTags.
 *   - `Thread.setDefaultUncaughtExceptionHandler` chained onto whatever
 *     the host already had (Crashlytics-style).
 *   - Auto-populated debug_meta via /proc/self/maps + GNU build-id.
 *   - Persistent offline queue via SharedPreferences.
 *   - Batch POST to `/api/catch/events`.
 *
 * Later milestones add ANR detection, NDK signal handlers, and the
 * replay-on-error hook.
 */
object SankofaCatch : SankofaPluggableModule {

    override val canonicalName: SankofaModuleName = SankofaModuleName.CATCH

    private const val TAG = "SankofaCatch"
    private const val PREFS_NAME = "sankofa.catch"
    private const val KEY_QUEUE = "queue"
    private const val MAX_QUEUE_BYTES = 512 * 1024
    private const val FLUSH_INTERVAL_MS = 5_000L
    private const val BATCH_SIZE = 20

    private val stateLock = Any()
    private val buffer: ConcurrentLinkedDeque<CatchEvent> = ConcurrentLinkedDeque()
    private var environment: String = "live"
    private var releaseName: String? = null
    private var appVersion: String? = null
    private var enabled: Boolean = true
    private var errorSampleRate: Double = 1.0

    private var user: CatchUserContext? = null
    private val tags: MutableMap<String, String> = mutableMapOf()
    private val extra: MutableMap<String, Any?> = mutableMapOf()

    private val breadcrumbs = BreadcrumbRing(100)
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val handlerInstalled = AtomicBoolean(false)
    private val flusher = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "sankofa-catch-flusher").apply { isDaemon = true }
    }
    private var readFlagSnapshot: (() -> Map<String, String>?)? = null
    private var readConfigSnapshot: (() -> Map<String, Any?>?)? = null

    // ── Init ─────────────────────────────────────────────────────

    @JvmStatic
    @JvmOverloads
    fun init(
        context: Context,
        environment: String = "live",
        release: String? = null,
        appVersion: String? = null,
        captureUnhandled: Boolean = true,
        readFlagSnapshot: (() -> Map<String, String>?)? = null,
        readConfigSnapshot: (() -> Map<String, Any?>?)? = null,
    ) {
        synchronized(stateLock) {
            if (this.prefs == null) {
                this.prefs = context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                hydrateFromStorage()
            }
            this.environment = environment
            this.releaseName = release
            this.appVersion = appVersion
            this.readFlagSnapshot = readFlagSnapshot
            this.readConfigSnapshot = readConfigSnapshot
        }
        SankofaModuleRegistry.register(this)

        if (captureUnhandled) installGlobalHandler()

        flusher.scheduleAtFixedRate(
            { runCatching { flushInternal() } },
            FLUSH_INTERVAL_MS,
            FLUSH_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    // ── SankofaPluggableModule ──────────────────────────────────

    override suspend fun applyHandshake(config: Map<String, Any?>) {
        val cfg = CatchHandshakeConfig.fromWire(config)
        synchronized(stateLock) {
            if (cfg.enabled == false) {
                this.enabled = false
                return
            }
            this.enabled = true
            cfg.errorSampleRate?.let { this.errorSampleRate = it.coerceIn(0.0, 1.0) }
            cfg.breadcrumbsMaxBuffer?.let { breadcrumbs.setCapacity(it) }
        }
    }

    // ── Public API ──────────────────────────────────────────────

    @JvmStatic
    @JvmOverloads
    fun captureException(t: Throwable, options: CatchCaptureOptions = CatchCaptureOptions()): String {
        return capture(
            kind = CaptureKind.Throwable(t),
            type = "unhandled_exception",
            options = options,
            mechanism = CatchMechanism(type = "manual", handled = true),
        )
    }

    @JvmStatic
    @JvmOverloads
    fun captureMessage(message: String, options: CatchCaptureOptions = CatchCaptureOptions()): String {
        return capture(
            kind = CaptureKind.Message(message),
            type = "console_error",
            options = options,
            mechanism = null,
        )
    }

    @JvmStatic
    fun addBreadcrumb(crumb: CatchBreadcrumb) = breadcrumbs.push(crumb)

    @JvmStatic
    fun setUser(u: CatchUserContext?) {
        synchronized(stateLock) { user = u }
    }

    @JvmStatic
    fun setTags(tags: Map<String, String>) {
        synchronized(stateLock) { this.tags.putAll(tags) }
    }

    @JvmStatic
    fun setExtra(key: String, value: Any?) {
        synchronized(stateLock) { this.extra[key] = value }
    }

    @JvmStatic
    fun flush() = flushInternal()

    // ── Capture path ───────────────────────────────────────────

    private sealed class CaptureKind {
        data class Throwable(val t: kotlin.Throwable) : CaptureKind()
        data class Message(val s: String) : CaptureKind()
    }

    private fun capture(
        kind: CaptureKind,
        type: String,
        options: CatchCaptureOptions,
        mechanism: CatchMechanism?,
    ): String {
        if (!enabled) return ""
        if (!shouldSample()) return ""

        val level = options.level ?: if (type == "console_error") CatchLevel.WARNING else CatchLevel.ERROR
        val (exception, message) = when (kind) {
            is CaptureKind.Throwable -> CatchStackBuilder.fromThrowable(kind.t, mechanism) to null
            is CaptureKind.Message -> null to kind.s
        }

        val event = CatchEvent(
            eventId = UUID.randomUUID().toString(),
            tsMs = System.currentTimeMillis(),
            environment = environment,
            level = level,
            type = type,
            platform = "android",
            sdk = CatchSDKInfo(name = "sankofa.android", version = "android-0.1.0"),
            exception = exception,
            message = message,
            tags = mergedTags(options),
            extra = mergedExtra(options),
            user = options.user ?: user,
            device = buildDeviceContext(),
            release = releaseName,
            breadcrumbs = breadcrumbs.snapshot(),
            fingerprint = options.fingerprint,
            flagSnapshot = readFlagSnapshot?.invoke(),
            configSnapshot = readConfigSnapshot?.invoke(),
            traceId = options.traceId,
            spanId = options.spanId,
            debugMeta = CatchDebugMetaCapture.capture(),
        )

        buffer.add(event)
        persistToStorage()
        if (buffer.size >= BATCH_SIZE) flushInternal()
        return event.eventId
    }

    private fun mergedTags(options: CatchCaptureOptions): Map<String, String>? {
        val merged = HashMap(tags)
        options.tags?.let { merged.putAll(it) }
        return merged.takeIf { it.isNotEmpty() }
    }

    private fun mergedExtra(options: CatchCaptureOptions): Map<String, Any?>? {
        val merged: MutableMap<String, Any?> = HashMap(extra)
        options.extra?.let { merged.putAll(it) }
        return merged.takeIf { it.isNotEmpty() }
    }

    private fun shouldSample(): Boolean {
        val r = errorSampleRate
        if (r >= 1) return true
        if (r <= 0) return false
        return Math.random() < r
    }

    private fun buildDeviceContext(): CatchDeviceContext {
        return CatchDeviceContext(
            os = "Android",
            osVersion = Build.VERSION.RELEASE,
            model = Build.MODEL,
            arch = Build.SUPPORTED_ABIS.firstOrNull(),
            appVersion = appVersion,
        )
    }

    // ── Transport ───────────────────────────────────────────────

    private fun flushInternal() {
        if (buffer.isEmpty()) return
        val endpoint = Sankofa.apiKey()?.let { Sankofa.endpoint() }
        val apiKey = Sankofa.apiKey()
        if (endpoint == null || apiKey == null) return

        val drained = mutableListOf<CatchEvent>()
        while (drained.size < BATCH_SIZE) {
            val e = buffer.pollFirst() ?: break
            drained.add(e)
        }
        if (drained.isEmpty()) return

        val url = URL("${endpoint.trimEnd('/')}/api/catch/events")
        val body = CatchJson.encodeBatch(drained)
        try {
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            if (code < 500) return // treat 4xx as "not worth retrying"
        } catch (err: Throwable) {
            Log.w(TAG, "flush failed, requeueing batch: ${err.message}")
        }
        // Requeue for next tick.
        for (e in drained.reversed()) buffer.addFirst(e)
        persistToStorage()
    }

    // ── Persistence ─────────────────────────────────────────────

    private fun hydrateFromStorage() {
        val raw = prefs?.getString(KEY_QUEUE, null) ?: return
        try {
            val arr = org.json.JSONArray(raw)
            // In M1 we only persist the RAW JSON payload — full
            // parsing + reconstruction would be expensive and
            // error-prone. Simpler: we drop persisted events on
            // restart if they're older than 24h, and we don't
            // attempt to decode back. Host apps that need cold-start
            // crash survival will get the full serialiser in M2.1.
            if (arr.length() > 0) {
                Log.i(TAG, "persisted queue present (${arr.length()} entries) — clearing on init")
                prefs?.edit()?.remove(KEY_QUEUE)?.apply()
            }
        } catch (_: Throwable) {
            prefs?.edit()?.remove(KEY_QUEUE)?.apply()
        }
    }

    private fun persistToStorage() {
        val snapshot = buffer.toList()
        if (snapshot.isEmpty()) {
            prefs?.edit()?.remove(KEY_QUEUE)?.apply()
            return
        }
        var serialised = CatchJson.encodeBatch(snapshot)
        while (serialised.length > MAX_QUEUE_BYTES && buffer.size > 1) {
            buffer.pollFirst()
            serialised = CatchJson.encodeBatch(buffer.toList())
        }
        prefs?.edit()?.putString(KEY_QUEUE, serialised)?.apply()
    }

    // ── Global handler ─────────────────────────────────────────

    private fun installGlobalHandler() {
        if (!handlerInstalled.compareAndSet(false, true)) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val event = CatchEvent(
                    eventId = UUID.randomUUID().toString(),
                    tsMs = System.currentTimeMillis(),
                    environment = environment,
                    level = CatchLevel.FATAL,
                    type = "unhandled_exception",
                    platform = "android",
                    sdk = CatchSDKInfo(name = "sankofa.android", version = "android-0.1.0"),
                    exception = CatchStackBuilder.fromThrowable(
                        throwable,
                        CatchMechanism(type = "uncaught_exception_handler", handled = false, description = "thread=${thread.name}"),
                    ),
                    user = user,
                    device = buildDeviceContext(),
                    release = releaseName,
                    breadcrumbs = breadcrumbs.snapshot(),
                    tags = tags.ifEmpty { null },
                    flagSnapshot = readFlagSnapshot?.invoke(),
                    configSnapshot = readConfigSnapshot?.invoke(),
                    debugMeta = CatchDebugMetaCapture.capture(),
                )
                buffer.addFirst(event)
                persistToStorage()
                // Best-effort synchronous flush so the event lands
                // before the process dies.
                flushInternal()
            } catch (err: Throwable) {
                Log.e(TAG, "uncaught handler itself threw: ${err.message}")
            }
            // Chain to the previous handler so Crashlytics / the
            // default "process died" report still fires.
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}

// ─── Ring buffer ─────────────────────────────────────────────────

private class BreadcrumbRing(capacity: Int) {
    @Volatile private var capacity: Int = capacity.coerceAtLeast(10)
    private val items = ArrayDeque<CatchBreadcrumb>()

    fun setCapacity(n: Int) {
        synchronized(items) {
            capacity = n.coerceAtLeast(10)
            while (items.size > capacity) items.removeFirst()
        }
    }

    fun push(b: CatchBreadcrumb) {
        synchronized(items) {
            items.addLast(b)
            if (items.size > capacity) items.removeFirst()
        }
    }

    fun snapshot(): List<CatchBreadcrumb> {
        synchronized(items) { return items.toList() }
    }
}
