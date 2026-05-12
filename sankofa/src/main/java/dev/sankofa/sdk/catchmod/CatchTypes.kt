package dev.sankofa.sdk.catchmod

// Sankofa Catch wire contract — Kotlin mirror of
// server/engine/ee/catch/wire.go. Renames in this file would be a
// wire-contract break; every SDK serialises to the same JSON.

const val CATCH_WIRE_VERSION = 1

enum class CatchLevel(val wireName: String) {
    FATAL("fatal"),
    ERROR("error"),
    WARNING("warning"),
    INFO("info"),
    DEBUG("debug"),
}

data class CatchSDKInfo(val name: String, val version: String)

data class CatchMechanism(
    val type: String,
    val handled: Boolean,
    val description: String? = null,
)

data class CatchStackFrame(
    val filename: String? = null,
    val function: String? = null,
    val module: String? = null,
    val lineno: Int? = null,
    val colno: Int? = null,
    val absPath: String? = null,
    val inApp: Boolean? = null,
    val platform: String? = null,
    val instructionAddr: String? = null,
    val pkg: String? = null,
    val symbol: String? = null,
    val symbolAddr: String? = null,
    val addrMode: String? = null,
)

data class CatchStackTrace(val frames: List<CatchStackFrame>)

data class CatchException(
    val type: String,
    val value: String,
    val module: String? = null,
    val mechanism: CatchMechanism? = null,
    val stacktrace: CatchStackTrace? = null,
    val chained: List<CatchException>? = null,
)

data class CatchUserContext(
    val id: String? = null,
    val email: String? = null,
    val username: String? = null,
    val ipAddress: String? = null,
    val segment: String? = null,
    val data: Map<String, String>? = null,
)

data class CatchDeviceContext(
    val os: String? = null,
    val osVersion: String? = null,
    val model: String? = null,
    val arch: String? = null,
    val memoryMb: Long? = null,
    val locale: String? = null,
    val country: String? = null,
    val timezone: String? = null,
    val appVersion: String? = null,
    val online: Boolean? = null,
)

data class CatchBreadcrumb(
    val tsMs: Long = System.currentTimeMillis(),
    val type: String,
    val category: String? = null,
    val message: String? = null,
    val level: CatchLevel? = null,
    val data: Map<String, Any?>? = null,
)

// Debug metadata — consumed by the M5 symbolicator worker to match
// native NDK frames to their unstripped .so files under ASLR.
data class CatchDebugImage(
    val type: String,               // "elf" for Android NDK binaries
    val debugId: String,            // GNU build-id (lowercased hex) or truncated
    val codeId: String? = null,
    val codeFile: String? = null,
    val imageAddr: String,          // "0x" + hex load address
    val imageSize: Long? = null,
    val imageVmaddr: String? = null,
    val arch: String? = null,
)

data class CatchDebugSDKInfo(
    val sdkName: String? = null,
    val versionMajor: Int? = null,
    val versionMinor: Int? = null,
    val versionPatchlevel: Int? = null,
)

data class CatchDebugMeta(
    val images: List<CatchDebugImage>? = null,
    val sdkInfo: CatchDebugSDKInfo? = null,
)

/** The full event envelope. Serialised to the frozen V1 wire shape. */
data class CatchEvent(
    val eventId: String,
    val tsMs: Long,
    val environment: String,
    val level: CatchLevel,
    val type: String,
    val platform: String,
    val sdk: CatchSDKInfo,

    val exception: CatchException? = null,
    val message: String? = null,

    val distinctId: String? = null,
    val anonId: String? = null,
    val sessionId: String? = null,

    val tags: Map<String, String>? = null,
    val extra: Map<String, Any?>? = null,
    val user: CatchUserContext? = null,
    val device: CatchDeviceContext? = null,
    val release: String? = null,

    val breadcrumbs: List<CatchBreadcrumb>? = null,
    val fingerprint: List<String>? = null,

    val flagSnapshot: Map<String, String>? = null,
    val configSnapshot: Map<String, Any?>? = null,

    val traceId: String? = null,
    val spanId: String? = null,
    val replayChunkIndex: Int? = null,
    val debugMeta: CatchDebugMeta? = null,
    /**
     * Active screen / route at capture time. Cross-product
     * correlation key shared with Heatmap, Replay, Pulse, and Plan.
     * Sourced from `Sankofa.currentScreen` (set explicitly via
     * `screen()` or by the `@SankofaScreen` annotation).
     */
    val screen: String? = null,
)

data class CatchHandshakeConfig(
    val enabled: Boolean? = null,
    val wireVersion: Int? = null,
    val ingestUrl: String? = null,
    val errorSampleRate: Double? = null,
    val transactionSampleRate: Double? = null,
    val profilesSampleRate: Double? = null,
    val replayOnErrorEnabled: Boolean? = null,
    val replayBurstSeconds: Int? = null,
    val breadcrumbsMaxBuffer: Int? = null,
    val reason: String? = null,
) {
    companion object {
        fun fromWire(raw: Map<String, Any?>?): CatchHandshakeConfig {
            if (raw == null) return CatchHandshakeConfig()
            @Suppress("UNCHECKED_CAST")
            val sampling = raw["sampling"] as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val replay = raw["replay"] as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val bc = raw["breadcrumbs"] as? Map<String, Any?>
            return CatchHandshakeConfig(
                enabled = raw["enabled"] as? Boolean,
                wireVersion = (raw["wire_version"] as? Number)?.toInt(),
                ingestUrl = raw["ingest_url"] as? String,
                errorSampleRate = (sampling?.get("error_sample_rate") as? Number)?.toDouble(),
                transactionSampleRate = (sampling?.get("transaction_sample_rate") as? Number)?.toDouble(),
                profilesSampleRate = (sampling?.get("profiles_sample_rate") as? Number)?.toDouble(),
                replayOnErrorEnabled = replay?.get("on_error_enabled") as? Boolean,
                replayBurstSeconds = (replay?.get("burst_seconds") as? Number)?.toInt(),
                breadcrumbsMaxBuffer = (bc?.get("max_buffer") as? Number)?.toInt(),
                reason = raw["reason"] as? String,
            )
        }
    }
}

data class CatchCaptureOptions(
    val level: CatchLevel? = null,
    val tags: Map<String, String>? = null,
    val extra: Map<String, Any?>? = null,
    val user: CatchUserContext? = null,
    val fingerprint: List<String>? = null,
    val traceId: String? = null,
    val spanId: String? = null,
)

/**
 * Synchronous hook called after an event has been composed but BEFORE
 * the SDK enqueues it for transport. Return the (possibly modified)
 * event to ship it; return `null` to drop it entirely.
 *
 * Use cases:
 *   - PII scrubbing (e.g. strip `event.user?.email`)
 *   - Noise filtering (e.g. drop known-benign exceptions)
 *   - Tag enrichment from state unavailable at SDK init time
 *
 * Throwing inside the hook is treated like returning the event
 * unchanged — a host bug must never block the capture pipeline.
 */
typealias BeforeSendFn = (CatchEvent) -> CatchEvent?

/**
 * Sentry-style temporary scope. Mutations made via the callback in
 * `SankofaCatch.withScope { scope -> ... }` overlay onto the next
 * captured event without polluting the global scope set via
 * `setUser` / `setTags` / `setExtra`.
 *
 * ```kotlin
 * SankofaCatch.withScope { scope ->
 *     scope.setTag("checkout_step", "payment")
 *     scope.setExtra("cart_id", cart.id)
 *     Sankofa.captureException(err)
 * }
 * // Outside the closure, those tags / extras are gone.
 * ```
 */
class SankofaCatchScope {
    private val _tags: MutableMap<String, String> = mutableMapOf()
    private val _extra: MutableMap<String, Any?> = mutableMapOf()
    private var _user: CatchUserContext? = null
    private var _userTouched = false
    private var _level: CatchLevel? = null
    private var _fingerprint: List<String>? = null

    fun setTag(key: String, value: String): SankofaCatchScope {
        _tags[key] = value
        return this
    }

    fun setTags(tags: Map<String, String>): SankofaCatchScope {
        _tags.putAll(tags)
        return this
    }

    fun setExtra(key: String, value: Any?): SankofaCatchScope {
        _extra[key] = value
        return this
    }

    fun setUser(user: CatchUserContext?): SankofaCatchScope {
        _user = user
        _userTouched = true
        return this
    }

    fun setLevel(level: CatchLevel): SankofaCatchScope {
        _level = level
        return this
    }

    fun setFingerprint(fingerprint: List<String>): SankofaCatchScope {
        _fingerprint = fingerprint.toList()
        return this
    }

    /**
     * Layer this scope on top of the caller's [options]. Caller
     * options take precedence (most specific wins); the global scope
     * set on the client is applied separately so this merge only
     * covers the scope ↔ options layer.
     */
    fun applyTo(options: CatchCaptureOptions): CatchCaptureOptions {
        val merged = HashMap<String, String>(_tags.size + (options.tags?.size ?: 0))
        merged.putAll(_tags)
        options.tags?.let { merged.putAll(it) }

        val mergedExtra = HashMap<String, Any?>(_extra.size + (options.extra?.size ?: 0))
        mergedExtra.putAll(_extra)
        options.extra?.let { mergedExtra.putAll(it) }

        return CatchCaptureOptions(
            level = options.level ?: _level,
            tags = if (merged.isEmpty()) null else merged,
            extra = if (mergedExtra.isEmpty()) null else mergedExtra,
            // User: caller wins, then scope. _userTouched distinguishes
            // "scope explicitly cleared user with setUser(null)" from
            // "scope never set user".
            user = options.user ?: if (_userTouched) _user else null,
            fingerprint = options.fingerprint ?: _fingerprint,
            traceId = options.traceId,
            spanId = options.spanId,
        )
    }
}
