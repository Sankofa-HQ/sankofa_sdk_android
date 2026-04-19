package dev.sankofa.sdk.remoteconfig

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.sankofa.sdk.core.SankofaModuleName
import dev.sankofa.sdk.core.SankofaModuleRegistry
import dev.sankofa.sdk.core.SankofaPluggableModule
import dev.sankofa.sdk.switchmod.Cancellation
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sankofa Remote Config on Android.
 *
 * NOTE on the name: `SankofaConfig` is already taken by the init-
 * options `data class` in `dev.sankofa.sdk`. Rather than park a second
 * `SankofaConfig` in a different package and force every consumer to
 * alias their imports, this class is deliberately named
 * `SankofaRemoteConfig` — matching the iOS SDK for cross-platform
 * consistency. Web/RN/Flutter keep the shorter `SankofaConfig` name
 * because their init-options type lives elsewhere.
 *
 * Usage:
 * ```kotlin
 * Sankofa.init(context, apiKey = "sk_live_...")
 * SankofaRemoteConfig.init(context)
 *
 * val maxMB: Int = SankofaRemoteConfig.get("max_upload_mb", 25)
 * val pricing: Map<String, Any?> = SankofaRemoteConfig.get("pricing", mapOf("pro" to 9.99))
 * ```
 */
object SankofaRemoteConfig : SankofaPluggableModule {

    private const val TAG = "SankofaRemoteConfig"
    private const val PREFS_NAME = "sankofa.config"
    private const val KEY_STATE = "state"
    private const val STALE_MAX_MS = 7L * 24 * 60 * 60 * 1000

    override val canonicalName: SankofaModuleName = SankofaModuleName.CONFIG

    private val stateLock = Any()
    private var values: Map<String, ItemDecision> = emptyMap()
    private var etag: String = ""
    private var savedAtMs: Long = 0
    private var defaults: Map<String, ItemDecision> = emptyMap()

    private val listeners: MutableMap<String, MutableMap<UUID, ConfigChangeListener>> =
        ConcurrentHashMap()

    @Volatile private var prefs: SharedPreferences? = null

    @JvmOverloads
    fun init(context: Context, defaults: Map<String, ItemDecision> = emptyMap()) {
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

    override suspend fun applyHandshake(config: Map<String, Any?>) {
        if (config["enabled"] == false) return
        @Suppress("UNCHECKED_CAST")
        val raw = config["values"] as? Map<String, Map<String, Any?>> ?: emptyMap()
        val incoming = mutableMapOf<String, ItemDecision>()
        for ((key, value) in raw) {
            ItemDecision.fromWire(value)?.let { incoming[key] = it }
        }
        val tag = config["etag"] as? String ?: ""
        val (changed, removed) = diffAndApply(incoming, tag)
        persistToStorage()
        fire(changed, removed)
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Typed lookup. Returns [defaultValue] when the key is missing,
     * null, or when the stored value can't be cast to [T].
     *
     * Handles JSON↔Kotlin numeric coercion because JSON doesn't
     * differentiate widths: an `int` stored as `Long` still returns
     * cleanly for `Int`, `Long`, or `Double` generic calls.
     */
    @JvmStatic
    fun <T> get(key: String, defaultValue: T): T {
        val decision = resolve(key) ?: return defaultValue
        val raw = decision.value ?: return defaultValue
        @Suppress("UNCHECKED_CAST")
        return coerce(raw, defaultValue) ?: defaultValue
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> coerce(raw: Any, fallback: T): T? {
        // Try the happy path first.
        if (raw::class.isInstance(fallback) || fallback == null) {
            (raw as? T)?.let { return it }
        }
        // JSON number flavour coercion — parsed numbers come out as
        // Double/Long/Int depending on OkHttp/Gson flavour; accept any.
        if (fallback is Int && raw is Number) return raw.toInt() as T
        if (fallback is Long && raw is Number) return raw.toLong() as T
        if (fallback is Double && raw is Number) return raw.toDouble() as T
        if (fallback is Float && raw is Number) return raw.toFloat() as T
        // Fall-through: try the plain cast; null means "not compatible".
        return raw as? T
    }

    @JvmStatic
    fun getDecision(key: String): ItemDecision? = resolve(key)

    @JvmStatic
    fun getAllKeys(): List<String> = synchronized(stateLock) {
        (values.keys + defaults.keys).toList()
    }

    /**
     * Plain `{ key: rawValue }` snapshot — convenient for "replace my
     * global settings object on handshake refresh" flows.
     */
    @JvmStatic
    fun getAll(): Map<String, Any?> = synchronized(stateLock) {
        val out = mutableMapOf<String, Any?>()
        for ((k, d) in defaults) out[k] = d.value
        for ((k, d) in values) out[k] = d.value
        out
    }

    @JvmStatic
    fun onChange(key: String, listener: ConfigChangeListener): Cancellation {
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

    @JvmStatic
    val currentEtag: String get() = synchronized(stateLock) { etag }

    // ── Internals ────────────────────────────────────────────────────

    private fun resolve(key: String): ItemDecision? = synchronized(stateLock) {
        values[key] ?: defaults[key]
    }

    private fun diffAndApply(incoming: Map<String, ItemDecision>, tag: String): Pair<Set<String>, Set<String>> {
        synchronized(stateLock) {
            val changed = mutableSetOf<String>()
            val removed = mutableSetOf<String>()
            for (key in values.keys) {
                if (!incoming.containsKey(key)) removed.add(key)
            }
            for ((key, decision) in incoming) {
                if (values[key] != decision) changed.add(key)
            }
            values = incoming.toMap()
            etag = tag
            savedAtMs = System.currentTimeMillis()
            return changed to removed
        }
    }

    private fun fire(changed: Set<String>, removed: Set<String>) {
        val snapshot = synchronized(stateLock) { values.toMap() }
        for (key in changed) {
            val bucket = listeners[key] ?: continue
            val decision = snapshot[key]
            synchronized(bucket) {
                for (listener in bucket.values.toList()) {
                    runCatching { listener.onChange(decision) }
                        .onFailure { Log.w(TAG, "onChange(\"$key\") threw: ${it.message}") }
                }
            }
        }
        for (key in removed) {
            val bucket = listeners[key] ?: continue
            synchronized(bucket) {
                for (listener in bucket.values.toList()) {
                    runCatching { listener.onChange(null) }
                        .onFailure { Log.w(TAG, "onChange remove(\"$key\") threw: ${it.message}") }
                }
            }
        }
    }

    // ── Storage ──────────────────────────────────────────────────────

    private fun hydrateFromStorage() {
        val raw = prefs?.getString(KEY_STATE, null) ?: return
        try {
            val root = JSONObject(raw)
            val savedAt = root.optLong("savedAt", 0)
            if (savedAt > 0 && System.currentTimeMillis() - savedAt > STALE_MAX_MS) {
                return
            }
            val valuesJson = root.optJSONObject("values") ?: return
            val rebuilt = mutableMapOf<String, ItemDecision>()
            val keys = valuesJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = valuesJson.optJSONObject(key) ?: continue
                ItemDecision.fromWire(jsonToMap(entry))?.let { rebuilt[key] = it }
            }
            values = rebuilt
            etag = root.optString("etag", "")
            savedAtMs = savedAt
        } catch (_: Exception) {
            prefs?.edit()?.remove(KEY_STATE)?.apply()
        }
    }

    private fun persistToStorage() {
        val current = prefs ?: return
        try {
            val valuesJson = JSONObject()
            synchronized(stateLock) {
                for ((key, decision) in values) {
                    val entry = JSONObject()
                    entry.put("value", wrapForJson(decision.value))
                    entry.put("type", decision.type.wireName)
                    entry.put("reason", decision.reason.wireName)
                    entry.put("version", decision.version)
                    valuesJson.put(key, entry)
                }
                val root = JSONObject()
                    .put("values", valuesJson)
                    .put("etag", etag)
                    .put("savedAt", savedAtMs)
                current.edit().putString(KEY_STATE, root.toString()).apply()
            }
        } catch (_: Exception) {
            /* best-effort */
        }
    }

    /**
     * Wrap arbitrary Kotlin values for JSON serialisation. org.json
     * handles primitives, String, JSONObject, and JSONArray directly;
     * Maps and Lists need explicit conversion.
     */
    private fun wrapForJson(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((k, v) in value) {
                    if (k is String) obj.put(k, wrapForJson(v))
                }
                obj
            }
            is List<*> -> {
                val arr = JSONArray()
                for (v in value) arr.put(wrapForJson(v))
                arr
            }
            else -> value
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
