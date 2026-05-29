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
    private var anrWatcher: CatchAnrWatcher? = null
    private val flusher = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "sankofa-catch-flusher").apply { isDaemon = true }
    }
    private var readFlagSnapshot: (() -> Map<String, String>?)? = null
    private var readConfigSnapshot: (() -> Map<String, Any?>?)? = null
    private var beforeSend: BeforeSendFn? = null

    // Active withScope() stack. ThreadLocal so concurrent threads
    // each see their own scope — captures fired on a background
    // worker don't pick up the UI thread's scope, and vice versa.
    private val scopeStack: ThreadLocal<ArrayDeque<SankofaCatchScope>> =
        object : ThreadLocal<ArrayDeque<SankofaCatchScope>>() {
            override fun initialValue(): ArrayDeque<SankofaCatchScope> = ArrayDeque()
        }

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
        beforeSend: BeforeSendFn? = null,
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
            this.beforeSend = beforeSend
        }
        SankofaModuleRegistry.register(this)

        if (captureUnhandled) {
            installGlobalHandler()
            // ANR watcher runs alongside the uncaught-exception handler
            // because an ANR doesn't throw — the main thread just
            // stops draining its queue. Without this, Android's own
            // ANR dialog is the first signal anyone gets that
            // something's wrong.
            // Stop any watcher from a prior init() before replacing it, so a
            // re-init doesn't leak the old daemon thread.
            anrWatcher?.stop()
            anrWatcher = CatchAnrWatcher(capture = { exc ->
                captureExceptionInternal(exc, type = "anr", level = CatchLevel.ERROR)
            }).also { it.start() }
        }

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
                // Server turned Catch off — stop the ANR watchdog thread too,
                // rather than leaving it spinning + walking stacks forever.
                anrWatcher?.stop()
                anrWatcher = null
                return
            }
            this.enabled = true
            cfg.errorSampleRate?.let { this.errorSampleRate = it.coerceIn(0.0, 1.0) }
            cfg.breadcrumbsMaxBuffer?.let { breadcrumbs.setCapacity(it) }
        }
    }

    /**
     * Self-audit the host's Catch wiring. Mirrors the RN + Flutter
     * audits — minimal but real checks that the dashboard SDK Health
     * surface can render.
     */
    fun checkIntegration(): dev.sankofa.sdk.core.ModuleIntegrationStatus {
        val missing = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (prefs == null) {
            missing.add(
                "SankofaCatch.init() has not run — the prefs handle is null. Make sure your Application's onCreate calls Sankofa.init() before any Catch usage.",
            )
        }
        if (!enabled) {
            missing.add(
                "Catch is disabled by the server-side handshake. Open Project Settings → Catch in the dashboard or check the org plan tier.",
            )
        }
        if (!handlerInstalled.get()) {
            warnings.add(
                "Uncaught-exception handler is not installed — only manually captured errors will surface. Call init(..., captureUnhandled = true).",
            )
        }
        if (errorSampleRate <= 0) {
            warnings.add(
                "errorSampleRate is $errorSampleRate — every captured event will be sampled out.",
            )
        }

        return dev.sankofa.sdk.core.ModuleIntegrationStatus(
            module = "catch",
            level = dev.sankofa.sdk.core.ModuleIntegrationStatus.deriveLevel(missing),
            missing = missing,
            warnings = warnings,
        )
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

    /**
     * Crashlytics-style breadcrumb log. Drops a free-text trail entry
     * onto the ring buffer that rides on the next captured event.
     * Doesn't bill: no event is emitted unless something else captures.
     */
    @JvmStatic
    @JvmOverloads
    fun log(message: String, category: String? = null) {
        breadcrumbs.push(
            CatchBreadcrumb(
                type = "log",
                category = category ?: "log",
                message = message,
                level = CatchLevel.INFO,
            )
        )
    }

    @JvmStatic
    fun setUser(u: CatchUserContext?) {
        synchronized(stateLock) { user = u }
    }

    @JvmStatic
    fun setTags(tags: Map<String, String>) {
        synchronized(stateLock) { this.tags.putAll(tags) }
    }

    /** Single-key convenience over [setTags]. */
    @JvmStatic
    fun setTag(key: String, value: String) {
        synchronized(stateLock) { this.tags[key] = value }
    }

    @JvmStatic
    fun setExtra(key: String, value: Any?) {
        synchronized(stateLock) { this.extra[key] = value }
    }

    @JvmStatic
    fun flush() = flushInternal()

    /**
     * Run [fn] with a fresh scope. Mutations made via the scope
     * (tags, extras, user, level, fingerprint) overlay onto any
     * [captureException] / [captureMessage] calls inside [fn]. The
     * scope is popped when [fn] returns — async captures deferred
     * past the closure's return will NOT see the scope.
     *
     * Thread-isolated: each thread has its own scope stack, so a
     * capture fired on a background worker won't pick up the UI
     * thread's scope, and vice versa.
     */
    fun <T> withScope(fn: (SankofaCatchScope) -> T): T {
        val scope = SankofaCatchScope()
        val stack = scopeStack.get()!!
        stack.addLast(scope)
        try {
            return fn(scope)
        } finally {
            stack.removeLast()
            if (stack.isEmpty()) scopeStack.remove()
        }
    }

    /**
     * True once [init] has been called at least once. Static helpers on
     * the parent [Sankofa] object check this so they can degrade to a
     * no-op when the host disabled Catch via `enableCatch = false` —
     * keeps `Sankofa.captureException(e)` safe to call from anywhere
     * without a guard.
     */
    @JvmStatic
    val isStarted: Boolean
        get() = prefs != null

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

        // Apply active withScope() overlay (if any). Layering order:
        //   global setUser/setTags/setExtra (read below)
        //   → top-of-stack scope (applyTo here)
        //   → caller-supplied options (already merged in by applyTo)
        val scope = scopeStack.get()?.lastOrNull()
        val merged = scope?.applyTo(options) ?: options

        val level = merged.level ?: if (type == "console_error") CatchLevel.WARNING else CatchLevel.ERROR
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
            sdk = CatchSDKInfo(name = "sankofa.android", version = dev.sankofa.sdk.SankofaVersion.LIB_VERSION),
            exception = exception,
            message = message,
            // Identity correlation — pulled from the core SDK so
            // Analytics + Replay + Catch events for the same session
            // join up in the dashboard. Null is tolerable server-side
            // when the host SDK hasn't initialised yet.
            distinctId = dev.sankofa.sdk.Sankofa.distinctId(),
            anonId = dev.sankofa.sdk.Sankofa.anonymousId(),
            sessionId = dev.sankofa.sdk.Sankofa.currentSessionId(),
            tags = mergedTags(merged),
            extra = mergedExtra(merged),
            user = merged.user ?: user,
            device = buildDeviceContext(),
            release = releaseName,
            breadcrumbs = breadcrumbs.snapshot(),
            fingerprint = merged.fingerprint,
            flagSnapshot = readFlagSnapshot?.invoke() ?: autoFlagSnapshot(),
            configSnapshot = readConfigSnapshot?.invoke() ?: autoConfigSnapshot(),
            traceId = merged.traceId,
            spanId = merged.spanId,
            debugMeta = CatchDebugMetaCapture.capture(),
            screen = dev.sankofa.sdk.Sankofa.currentScreenName(),
        )

        val outgoing = applyBeforeSend(event)
        if (outgoing == null) {
            Log.d(TAG, "event ${event.eventId} dropped by beforeSend")
            return ""
        }

        buffer.add(outgoing)
        persistToStorage()
        if (buffer.size >= BATCH_SIZE) flushInternal()
        return outgoing.eventId
    }

    /**
     * Internal capture path for pre-built CatchException values —
     * used by the ANR watcher + signal-dump replay, both of which
     * have a synthetic exception rather than a real Throwable.
     *
     * Runs the same event-envelope + identity correlation + debug_meta
     * build as the throwable/message path.
     */
    internal fun captureExceptionInternal(
        exception: CatchException,
        type: String,
        level: CatchLevel,
    ): String {
        if (!enabled) return ""
        if (!shouldSample()) return ""

        val event = CatchEvent(
            eventId = UUID.randomUUID().toString(),
            tsMs = System.currentTimeMillis(),
            environment = environment,
            level = level,
            type = type,
            platform = "android",
            sdk = CatchSDKInfo(name = "sankofa.android", version = dev.sankofa.sdk.SankofaVersion.LIB_VERSION),
            exception = exception,
            distinctId = dev.sankofa.sdk.Sankofa.distinctId(),
            anonId = dev.sankofa.sdk.Sankofa.anonymousId(),
            sessionId = dev.sankofa.sdk.Sankofa.currentSessionId(),
            tags = tags.ifEmpty { null },
            user = user,
            device = buildDeviceContext(),
            release = releaseName,
            breadcrumbs = breadcrumbs.snapshot(),
            flagSnapshot = readFlagSnapshot?.invoke() ?: autoFlagSnapshot(),
            configSnapshot = readConfigSnapshot?.invoke() ?: autoConfigSnapshot(),
            debugMeta = CatchDebugMetaCapture.capture(),
        )
        // ANR/synthetic events get the same host beforeSend treatment as the
        // throwable/message path — PII scrubbing configured by the host must
        // not be bypassed just because the event didn't originate as a Throwable.
        val outgoing = applyBeforeSend(event) ?: return ""
        buffer.add(outgoing)
        persistToStorage()
        if (buffer.size >= BATCH_SIZE) flushInternal()
        return outgoing.eventId
    }

    /**
     * Runs the host [beforeSend] hook. Returns the (possibly mutated) event,
     * or null to drop it. A throwing hook is treated as "pass through
     * unchanged" — losing telemetry to a buggy hook is worse than the hook
     * misbehaving. Applied on EVERY capture path (handled, message, ANR, and
     * fatal) so host PII scrubbing is never silently skipped.
     */
    private fun applyBeforeSend(event: CatchEvent): CatchEvent? {
        val hook = beforeSend ?: return event
        return try {
            hook.invoke(event)
        } catch (err: Throwable) {
            Log.w(TAG, "beforeSend threw — sending original event: ${err.message}")
            event
        }
    }

    /**
     * Best-effort flag snapshot. Used when the host didn't pass an
     * explicit `readFlagSnapshot` to [init]. Looks the Switch module
     * up via the registry — if Switch isn't linked, returns null and
     * the event ships without a flag snapshot.
     *
     * The cast through [SankofaPluggableModule] keeps this file from
     * pulling Switch's concrete API into the catch module's ABI. The
     * registry is the only contract.
     */
    private fun autoFlagSnapshot(): Map<String, String>? {
        val mod = SankofaModuleRegistry.get(SankofaModuleName.SWITCH)
            ?: return null
        if (mod !is dev.sankofa.sdk.switchmod.SankofaSwitch) return null
        val keys = mod.getAllKeys()
        if (keys.isEmpty()) return null
        val out = HashMap<String, String>(keys.size)
        for (k in keys) {
            val d = mod.getDecision(k) ?: continue
            // Variants render as the variant key; pure boolean flags
            // render as "true"/"false" so the dashboard can group on
            // the value without re-resolving.
            out[k] = if (d.variant.isNotEmpty()) d.variant else d.value.toString()
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** Symmetric to [autoFlagSnapshot] but for the RemoteConfig module. */
    private fun autoConfigSnapshot(): Map<String, Any?>? {
        val mod = SankofaModuleRegistry.get(SankofaModuleName.CONFIG)
            ?: return null
        if (mod !is dev.sankofa.sdk.remoteconfig.SankofaRemoteConfig) return null
        val keys = mod.getAllKeys()
        if (keys.isEmpty()) return null
        val out = HashMap<String, Any?>(keys.size)
        for (k in keys) {
            val d = mod.getDecision(k) ?: continue
            out[k] = d.value
        }
        return out.takeIf { it.isNotEmpty() }
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
        if (raw.isBlank()) {
            prefs?.edit()?.remove(KEY_QUEUE)?.apply()
            return
        }
        // The persisted blob is already the exact `/api/catch/events` wire body
        // (`CatchJson.encodeBatch` → `{ wire_version, events: [...] }`). Rather
        // than decode it back into CatchEvents, we resend it verbatim so a fatal
        // crash that happened while offline survives a cold start. This runs off
        // the init thread so a slow network never blocks SDK bring-up.
        Thread {
            val delivered = resendPersistedBatch(raw)
            if (delivered) {
                prefs?.edit()?.remove(KEY_QUEUE)?.apply()
            }
        }.apply {
            name = "sankofa-catch-hydrate"
            isDaemon = true
        }.start()
    }

    /**
     * POSTs an already-serialised persisted crash batch to the Catch ingest
     * endpoint. Returns true when the batch was delivered or permanently
     * rejected (so it can be cleared); false on a transient failure (5xx /
     * network / no credentials yet), leaving it on disk for the next launch.
     */
    private fun resendPersistedBatch(rawBody: String): Boolean {
        val endpoint = Sankofa.endpoint() ?: return false
        val apiKey = Sankofa.apiKey() ?: return false
        return try {
            val url = URL("${endpoint.trimEnd('/')}/api/catch/events")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.outputStream.use { it.write(rawBody.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            // 2xx / 4xx → clear (delivered or unrecoverable); 5xx → keep for retry.
            code < 500
        } catch (err: Throwable) {
            Log.w(TAG, "resend persisted crash batch failed: ${err.message}")
            false
        }
    }

    /**
     * @param sync when true, the write is flushed synchronously via `commit()`.
     * The uncaught-exception handler MUST use this — `apply()` schedules the
     * disk write on a background thread that the imminent process death would
     * kill mid-flight, losing the very crash we're trying to persist.
     */
    private fun persistToStorage(sync: Boolean = false) {
        val editor = prefs?.edit() ?: return
        val snapshot = buffer.toList()
        if (snapshot.isEmpty()) {
            editor.remove(KEY_QUEUE)
        } else {
            var serialised = CatchJson.encodeBatch(snapshot)
            while (serialised.length > MAX_QUEUE_BYTES && buffer.size > 1) {
                buffer.pollFirst()
                serialised = CatchJson.encodeBatch(buffer.toList())
            }
            editor.putString(KEY_QUEUE, serialised)
        }
        if (sync) editor.commit() else editor.apply()
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
                    sdk = CatchSDKInfo(name = "sankofa.android", version = dev.sankofa.sdk.SankofaVersion.LIB_VERSION),
                    exception = CatchStackBuilder.fromThrowable(
                        throwable,
                        CatchMechanism(type = "uncaught_exception_handler", handled = false, description = "thread=${thread.name}"),
                    ),
                    distinctId = dev.sankofa.sdk.Sankofa.distinctId(),
                    anonId = dev.sankofa.sdk.Sankofa.anonymousId(),
                    sessionId = dev.sankofa.sdk.Sankofa.currentSessionId(),
                    user = user,
                    device = buildDeviceContext(),
                    release = releaseName,
                    breadcrumbs = breadcrumbs.snapshot(),
                    tags = tags.ifEmpty { null },
                    flagSnapshot = readFlagSnapshot?.invoke() ?: autoFlagSnapshot(),
                    configSnapshot = readConfigSnapshot?.invoke() ?: autoConfigSnapshot(),
                    debugMeta = CatchDebugMetaCapture.capture(),
                )
                // Run beforeSend even on the fatal path so host PII scrubbing
                // applies to crashes too. A null return honours the host's
                // explicit choice to drop; anything else is persisted.
                val outgoing = applyBeforeSend(event)
                if (outgoing != null) {
                    buffer.addFirst(outgoing)
                    // Synchronous commit — the process is about to die, so an
                    // async apply() would lose the write. This is the durable
                    // record; the network flush below is best-effort on top.
                    persistToStorage(sync = true)
                    // Best-effort synchronous flush so the event lands
                    // before the process dies. If it fails (offline / hung
                    // socket), the committed copy above is resent on next launch.
                    flushInternal()
                }
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
