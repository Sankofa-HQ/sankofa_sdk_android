package dev.sankofa.sdk.switchmod

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.sankofa.sdk.core.SankofaModuleName
import dev.sankofa.sdk.core.SankofaModuleRegistry
import dev.sankofa.sdk.core.SankofaPluggableModule
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sankofa Switch — feature flags on Android.
 *
 * Usage:
 * ```kotlin
 * Sankofa.init(context, apiKey = "sk_live_...")
 * SankofaSwitch.init(context)
 * if (SankofaSwitch.getFlag("new_checkout")) {
 *     showNewUi()
 * }
 * ```
 *
 * The object singleton self-registers with the Traffic Cop on
 * [init] so the handshake response's `modules.switch` payload flows
 * here automatically. Calls made before the first handshake
 * completes return the supplied default or the last persisted
 * decision from SharedPreferences (7-day stale-while-revalidate).
 *
 * Why an `object` (not a `class`): feature-flag state is inherently
 * global — calls from anywhere in the app should see the same
 * decisions. A singleton matches that semantic cleanly and avoids
 * the lifecycle question of "who owns the instance." Same shape as
 * the iOS `SankofaSwitch.shared` and Web `getSwitch()`.
 */
object SankofaSwitch : SankofaPluggableModule {

    private const val TAG = "SankofaSwitch"
    private const val PREFS_NAME = "sankofa.switch"
    private const val KEY_STATE = "state"
    private const val STALE_MAX_MS = 7L * 24 * 60 * 60 * 1000

    override val canonicalName: SankofaModuleName = SankofaModuleName.SWITCH

    // ── Mutable state (all reads/writes gated by `stateLock`) ────────

    private val stateLock = Any()
    private var flags: Map<String, FlagDecision> = emptyMap()
    private var etag: String = ""
    private var savedAtMs: Long = 0
    private var defaults: Map<String, FlagDecision> = emptyMap()

    // Listeners use ConcurrentHashMap directly — contention is low
    // (handshake fires once every few minutes) and we want to allow
    // concurrent `getFlag` reads without blocking on listener mutation.
    private val listeners: MutableMap<String, MutableMap<UUID, FlagChangeListener>> =
        ConcurrentHashMap()

    @Volatile private var prefs: SharedPreferences? = null

    // ── Init ─────────────────────────────────────────────────────────

    /**
     * Initialise with application context. Required before any
     * `getFlag` call that expects cached values. Registers with the
     * Traffic Cop on first call; subsequent calls are idempotent.
     *
     * The cache is hydrated synchronously off SharedPreferences (fast,
     * microseconds) so the very first read after `init` already sees
     * persisted values. The actual handshake network call is driven
     * by `Sankofa.init` separately.
     */
    @JvmOverloads
    fun init(context: Context, defaults: Map<String, FlagDecision> = emptyMap()) {
        synchronized(stateLock) {
            if (this.prefs == null) {
                this.prefs = context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                hydrateFromStorage()
            }
            if (defaults.isNotEmpty()) {
                this.defaults = defaults
            }
        }
        SankofaModuleRegistry.register(this)
    }

    // ── SankofaPluggableModule ───────────────────────────────────────

    override suspend fun applyHandshake(config: Map<String, Any?>) {
        if (config["enabled"] == false) return
        @Suppress("UNCHECKED_CAST")
        val raw = config["flags"] as? Map<String, Map<String, Any?>> ?: emptyMap()
        val incoming = mutableMapOf<String, FlagDecision>()
        for ((key, value) in raw) {
            FlagDecision.fromWire(value)?.let { incoming[key] = it }
        }
        val tag = config["etag"] as? String ?: ""
        val diff = diffAndApply(incoming, tag)
        persistToStorage()
        fire(diff)
    }

    /** Self-audit the host's Switch wiring. */
    fun checkIntegration(): dev.sankofa.sdk.core.ModuleIntegrationStatus {
        val missing = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        synchronized(stateLock) {
            if (prefs == null) {
                missing.add(
                    "SankofaSwitch.init(context) has not been called — the prefs handle is null. Call it from your Application.onCreate.",
                )
            }
            if (flags.isEmpty() && defaults.isEmpty()) {
                missing.add(
                    "No flags from server and no bundled defaults supplied. Every getFlag(key) returns the inline fallback value.",
                )
            } else if (flags.isEmpty()) {
                warnings.add(
                    "No flags from server yet — handshake may not have completed, or the project has no flags. Defaults will be used.",
                )
            }
            if (etag.isEmpty()) {
                warnings.add(
                    "No etag stored — the SDK cannot short-circuit handshakes with If-None-Match.",
                )
            }
        }
        return dev.sankofa.sdk.core.ModuleIntegrationStatus(
            module = "switch",
            level = dev.sankofa.sdk.core.ModuleIntegrationStatus.deriveLevel(missing),
            missing = missing,
            warnings = warnings,
        )
    }

    // ── Public API ───────────────────────────────────────────────────

    @JvmStatic
    @JvmOverloads
    fun getFlag(key: String, defaultValue: Boolean = false): Boolean {
        val decision = resolve(key) ?: return defaultValue
        return decision.value
    }

    @JvmStatic
    @JvmOverloads
    fun getVariant(key: String, defaultValue: String = ""): String {
        val decision = resolve(key) ?: return defaultValue
        return decision.variant.ifEmpty { defaultValue }
    }

    @JvmStatic
    fun getDecision(key: String): FlagDecision? = resolve(key)

    @JvmStatic
    fun getAllKeys(): List<String> = synchronized(stateLock) {
        (flags.keys + defaults.keys).toList()
    }

    /**
     * Subscribe to changes for one flag. Returns a `Cancellation`; call
     * `cancel()` to remove the listener. The listener fires when a
     * handshake refresh delivers a new decision, OR when the flag is
     * removed from the config (callback receives null).
     */
    @JvmStatic
    fun onChange(key: String, listener: FlagChangeListener): Cancellation {
        val id = UUID.randomUUID()
        val bucket = listeners.getOrPut(key) { Collections.synchronizedMap(mutableMapOf()) }
        bucket[id] = listener
        return Cancellation {
            listeners[key]?.let { b ->
                b.remove(id)
                if (b.isEmpty()) listeners.remove(key)
            }
        }
    }

    /** Composite etag from the last successful handshake. */
    @JvmStatic
    val currentEtag: String get() = synchronized(stateLock) { etag }

    // ── Internals ────────────────────────────────────────────────────

    private fun resolve(key: String): FlagDecision? = synchronized(stateLock) {
        flags[key] ?: defaults[key]
    }

    /** A computed diff with the changed values captured atomically under the lock. */
    private class Diff(
        val changed: Map<String, FlagDecision?>,
        val removed: Set<String>,
    )

    private fun diffAndApply(incoming: Map<String, FlagDecision>, tag: String): Diff {
        synchronized(stateLock) {
            val changed = LinkedHashMap<String, FlagDecision?>()
            val removed = mutableSetOf<String>()
            for (key in flags.keys) {
                if (!incoming.containsKey(key)) removed.add(key)
            }
            for ((key, decision) in incoming) {
                if (flags[key] != decision) changed[key] = decision
            }
            flags = incoming.toMap()
            etag = tag
            savedAtMs = System.currentTimeMillis()
            return Diff(changed, removed)
        }
    }

    private fun fire(diff: Diff) {
        // Changed values captured under the lock, so listeners can't observe a
        // racing concurrent handshake's snapshot.
        for ((key, decision) in diff.changed) {
            val bucket = listeners[key] ?: continue
            synchronized(bucket) {
                for (listener in bucket.values.toList()) {
                    runCatching { listener.onChange(decision) }
                        .onFailure { Log.w(TAG, "onChange(\"$key\") threw: ${it.message}") }
                }
            }
        }
        for (key in diff.removed) {
            val bucket = listeners[key] ?: continue
            synchronized(bucket) {
                for (listener in bucket.values.toList()) {
                    runCatching { listener.onChange(null) }
                        .onFailure { Log.w(TAG, "onChange remove(\"$key\") threw: ${it.message}") }
                }
            }
        }
    }

    // ── Storage (SharedPreferences-backed) ───────────────────────────

    private fun hydrateFromStorage() {
        val raw = prefs?.getString(KEY_STATE, null) ?: return
        try {
            val root = JSONObject(raw)
            val savedAt = root.optLong("savedAt", 0)
            if (savedAt > 0 && System.currentTimeMillis() - savedAt > STALE_MAX_MS) {
                // Cache too old — drop rather than serve stale values.
                return
            }
            val flagsJson = root.optJSONObject("flags") ?: return
            val rebuilt = mutableMapOf<String, FlagDecision>()
            val keys = flagsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = flagsJson.optJSONObject(key) ?: continue
                val asMap = jsonToMap(entry)
                FlagDecision.fromWire(asMap)?.let { rebuilt[key] = it }
            }
            flags = rebuilt
            etag = root.optString("etag", "")
            savedAtMs = savedAt
        } catch (e: Exception) {
            // Corrupt JSON — clear the slot so the next persist writes clean.
            prefs?.edit()?.remove(KEY_STATE)?.apply()
        }
    }

    private fun persistToStorage() {
        val current = prefs ?: return
        try {
            val flagsJson = JSONObject()
            synchronized(stateLock) {
                for ((key, decision) in flags) {
                    flagsJson.put(key, JSONObject(decision.toWire()))
                }
                val root = JSONObject()
                    .put("flags", flagsJson)
                    .put("etag", etag)
                    .put("savedAt", savedAtMs)
                current.edit().putString(KEY_STATE, root.toString()).apply()
            }
        } catch (_: Exception) {
            // best-effort; in-memory state stays correct
        }
    }

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value: Any? = when (val raw = obj.get(key)) {
                JSONObject.NULL -> null
                is JSONObject -> jsonToMap(raw)
                is JSONArray -> jsonArrayToList(raw)
                else -> raw
            }
            out[key] = value
        }
        return out
    }

    private fun jsonArrayToList(arr: JSONArray): List<Any?> {
        val out = mutableListOf<Any?>()
        for (i in 0 until arr.length()) {
            when (val raw = arr.get(i)) {
                JSONObject.NULL -> out.add(null)
                is JSONObject -> out.add(jsonToMap(raw))
                is JSONArray -> out.add(jsonArrayToList(raw))
                else -> out.add(raw)
            }
        }
        return out
    }
}

/**
 * Token returned by [SankofaSwitch.onChange]. Hold it to keep the
 * listener alive; call [cancel] to remove it.
 */
class Cancellation internal constructor(private var action: (() -> Unit)?) {
    fun cancel() {
        action?.invoke()
        action = null
    }
}
