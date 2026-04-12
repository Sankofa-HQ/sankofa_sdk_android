package dev.sankofa.sdk

import android.content.Context
import com.google.gson.Gson
import dev.sankofa.sdk.core.SankofaDeviceInfo
import dev.sankofa.sdk.core.SankofaIdentity
import dev.sankofa.sdk.core.SankofaLifecycleObserver
import dev.sankofa.sdk.core.SankofaSessionManager
import dev.sankofa.sdk.data.EventQueueManager
import dev.sankofa.sdk.network.SankofaHttpClient
import dev.sankofa.sdk.network.SyncWorker
import dev.sankofa.sdk.network.SankofaCommand
import dev.sankofa.sdk.network.SankofaResponse
import dev.sankofa.sdk.replay.BitmapPool
import dev.sankofa.sdk.replay.ReplayConfig
import dev.sankofa.sdk.replay.ReplayRecorder
import dev.sankofa.sdk.replay.ReplayUploader
import dev.sankofa.sdk.util.SankofaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.TimeZone
import kotlin.random.Random

/**
 * # Sankofa Analytics SDK
 *
 * The single entry point for all SDK functionality.
 *
 * ## Quick Start
 * ```kotlin
 * class MyApplication : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Sankofa.init(
 *             context = this,
 *             apiKey = "sk_live_12345",
 *             config = SankofaConfig(recordSessions = true, maskAllInputs = true)
 *         )
 *     }
 * }
 * ```
 *
 * ## Developer Privacy API
 * Tag any view with `view.tag = "sankofa_mask"` to automatically redact it from recordings.
 * All [android.widget.EditText] fields are masked automatically when [SankofaConfig.maskAllInputs] is true.
 */
object Sankofa {

    private lateinit var logger: SankofaLogger
    private lateinit var identity: SankofaIdentity
    private lateinit var sessionManager: SankofaSessionManager
    private lateinit var queueManager: EventQueueManager
    private lateinit var lifecycleObserver: SankofaLifecycleObserver
    private lateinit var replayUploader: ReplayUploader
    private lateinit var replayRecorder: ReplayRecorder
    private lateinit var httpClient: SankofaHttpClient
    private lateinit var config: SankofaConfig
    private lateinit var appContext: Context

    private var defaultProperties: Map<String, Any> = emptyMap()
    private var replayConfig: ReplayConfig? = null
    private var isInitialized = false

    /**
     * The current screen name for stateful tagging (Heatmaps, Replays).
     *
     * Empty string is the sentinel for "not yet tagged". The replay
     * recorder and uploader skip frames + touch events while this is
     * empty so they're never attributed to the wrong screen.
     */
    private var currentScreen: String = ""
    private var isManualScreen: Boolean = false

    internal fun getCurrentScreenName(): String = currentScreen

    /**
     * Returns true when [currentScreen] has not been tagged yet (either
     * via [SankofaScreen] annotation, manual [screen] call, or activity
     * fallback). Used by the replay recorder to skip cold-start frames
     * that would otherwise be tagged with the wrong screen name.
     */
    internal fun hasTaggedScreen(): Boolean = currentScreen.isNotEmpty()

    private val scope = CoroutineScope(Dispatchers.IO)
    private val gson = Gson()

    private var lastActivityHash: Int = 0

    /**
     * Activity class names that should NEVER be used as automatic
     * screen tags, because they're framework host activities — there's
     * exactly one of them per app and they tell us nothing about which
     * UI the user is actually viewing. Screen tagging in these
     * frameworks is the host language's responsibility:
     *
     *  - React Native: `useSankofaScreen` / `Sankofa.screen()` from JS
     *  - Flutter: `SankofaNavigatorObserver`
     *  - Compose: `SankofaScreen` annotation on the parent activity
     *    (which would shortcut this list anyway), or `Sankofa.screen()`
     *    from a NavHost listener
     *
     * If we DID auto-tag from these classes, every cold-start frame
     * would be attributed to "MainActivity" instead of the user's
     * actual screen, and the dashboard would show "Awaiting Snapshot"
     * because the screen the user navigated to in the dashboard never
     * appears in `screen_backgrounds`.
     */
    private val NON_TAGGABLE_HOST_ACTIVITIES = setOf(
        // React Native
        "MainActivity",
        "ReactActivity",
        "MainReactActivity",
        // Flutter
        "FlutterActivity",
        "FlutterFragmentActivity",
    )

    /**
     * Resolve the auto-tag screen name for an activity, returning
     * empty string when the activity is a framework host that should
     * be ignored. The annotation always wins if present.
     */
    private fun resolveAutoTag(activity: android.app.Activity): String {
        val annotation = activity::class.java.getAnnotation(SankofaScreen::class.java)
        if (annotation != null) return annotation.name
        val simpleName = activity.javaClass.simpleName
        return if (simpleName in NON_TAGGABLE_HOST_ACTIVITIES) "" else simpleName
    }

    internal fun onActivityCreated(activity: android.app.Activity) {
        // Called before the user's `onCreate` logic executes
        val hash = System.identityHashCode(activity)
        lastActivityHash = hash
        isManualScreen = false

        val tag = resolveAutoTag(activity)
        if (tag.isNotEmpty()) {
            currentScreen = tag
        }
        // If tag is empty (RN/Flutter host activity), preserve whatever
        // currentScreen was before — usually empty on first launch, or
        // the previous JS-set value on hot reload — and let the host
        // language tag it explicitly via Sankofa.screen().
    }

    internal fun onActivityResumed(activity: android.app.Activity) {
        val hash = System.identityHashCode(activity)

        if (lastActivityHash != hash) {
            // Crossed an activity boundary (e.g. going back in the backstack)
            lastActivityHash = hash
            isManualScreen = false
        }

        // Only apply the automatic tag if the user didn't explicitly call Sankofa.screen() on this activity
        if (!isManualScreen) {
            val tag = resolveAutoTag(activity)
            if (tag.isNotEmpty()) {
                currentScreen = tag
                logger.debug("📍 Auto-tagged screen: $currentScreen")
            }
            // Empty tag → framework host activity, leave currentScreen
            // alone so the host language's screen() call wins.
        }
    }

    internal fun currentIsoTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    /**
     * Initializes the SDK. Call this once inside [android.app.Application.onCreate].
     * Subsequent calls are no-ops unless [shutdown] has been called first.
     */
    @JvmStatic
    fun init(
        context: Context,
        apiKey: String,
        config: SankofaConfig = SankofaConfig(),
    ) {
        if (isInitialized) return

        this.config = config
        this.appContext = context.applicationContext

        logger = SankofaLogger(config.debug)
        identity = SankofaIdentity(appContext, logger)

        // Build endpoints
        val base = config.endpoint.trimEnd('/')
        httpClient = SankofaHttpClient(
            apiKey = apiKey,
            trackEndpoint = "$base/api/v1/track",
            aliasEndpoint = "$base/api/v1/alias",
            peopleEndpoint = "$base/api/v1/people",
            logger = logger,
        )

        queueManager = EventQueueManager(
            context = appContext,
            httpClient = httpClient,
            logger = logger,
            batchSize = config.batchSize,
        )

        // Wire SyncWorker → queueManager reference
        SyncWorker.queueManagerRef = queueManager

        queueManager.onCommandsReceived = { commands ->
            handleServerCommands(commands)
        }

        // Replay subsystem
        val bitmapPool = BitmapPool(logger)
        replayUploader = ReplayUploader(
            context = appContext,
            httpClient = httpClient,
            replayEndpoint = base,
            logger = logger,
        )
        replayRecorder = ReplayRecorder(
            logger = logger,
            bitmapPool = bitmapPool,
            maskAllInputs = config.maskAllInputs,
            uploader = replayUploader,
        )

        sessionManager = SankofaSessionManager(
            context = appContext,
            logger = logger,
            onNewSession = { sessionId -> onNewSessionStarted(apiKey, base, sessionId) },
        )

        lifecycleObserver = SankofaLifecycleObserver(
            application = appContext as android.app.Application,
            logger = logger,
            sessionManager = sessionManager,
            queueManager = queueManager,
            replayRecorder = replayRecorder,
            trackLifecycleEvents = config.trackLifecycleEvents,
            recordSessions = config.recordSessions,
            flushIntervalSeconds = config.flushIntervalSeconds,
            onTrack = { eventName -> track(eventName) },
        )

        // Collect static device properties once
        defaultProperties = SankofaDeviceInfo.getProperties(appContext)

        // Boot up
        scope.launch {
            sessionManager.refresh()

            // First Time Open Logic
            val prefs = appContext.getSharedPreferences("sankofa_internal", Context.MODE_PRIVATE)
            val isFirstOpen = !prefs.getBoolean("first_open_detected", false)
            if (isFirstOpen) {
                prefs.edit().putBoolean("first_open_detected", true).apply()
                track("\$app_open_first_time")
            }

            if (config.trackLifecycleEvents) {
                track("\$app_opened")
            }
            
            track("\$session_start")
            
            isInitialized = true
            logger.debug("⚡ Sankofa initialized (debug=${config.debug})")
        }

        lifecycleObserver.register()
    }

    // --- Public API ---

    /**
     * Explicitly tag the screen the user is currently viewing.
     * Used for heatmaps and behavioral context.
     */
    @JvmStatic
    @JvmOverloads
    fun screen(name: String, properties: Map<String, Any> = emptyMap()) {
        this.currentScreen = name
        this.isManualScreen = true
        track("\$screen_view", properties + mapOf("\$screen_name" to name))
    }

    /**
     * Tracks a custom event with optional [properties].
     * This is a fire-and-forget call – it returns immediately and writes to the
     * local Room DB on a background thread.
     */
    @JvmStatic
    @JvmOverloads
    fun track(eventName: String, properties: Map<String, Any> = emptyMap()) {
        if (!isInitialized && eventName != "\$app_opened" && eventName != "\$app_open_first_time" && eventName != "\$session_start") {
            logger.warn("❌ Sankofa.track() called before init()")
            return
        }

        scope.launch {
            if (isInitialized) sessionManager.refresh()

            val event = buildMap<String, Any> {
                put("type", "track")
                put("event_name", eventName)
                put("distinct_id", identity.distinctId)
                put("properties", buildMap<String, Any> {
                    put("\$session_id", sessionManager.sessionId)
                    put("\$screen_name", currentScreen)
                    putAll(properties)
                })
                put("default_properties", defaultProperties)
                put("timestamp", currentIsoTimestamp())
                put("lib_version", "android-0.1.0")
                put("message_id", UUID.randomUUID().toString())
            }

            queueManager.enqueue(event)
            logger.debug("📝 Tracked: $eventName")

            // High-fidelity trigger check
            val rc = replayConfig
            if (rc != null && rc.highFidelityTriggers.contains(eventName)) {
                logger.debug("🚀 High-fidelity trigger: $eventName")
                // Future: switch replay to high-fidelity mode
            }
        }
    }

    /**
     * Identifies the current user with a unique [userId].
     * Merges the anonymous session data with the identified user profile via an alias event.
     */
    @JvmStatic
    fun identify(userId: String) {
        assertInitialized("identify") ?: return
        scope.launch {
            val aliasEvent = identity.identify(userId)?.toMutableMap()
            if (aliasEvent != null) {
                val props = (aliasEvent["properties"] as? MutableMap<String, Any>) ?: mutableMapOf()
                props["\$session_id"] = sessionManager.sessionId
                props["\$screen_name"] = currentScreen
                aliasEvent["properties"] = props
                
                queueManager.enqueue(aliasEvent)
                queueManager.flush()
            }
            replayUploader.setDistinctId(userId)
        }
    }

    /**
     * Resets the current user identity and starts a fresh anonymous session.
     */
    @JvmStatic
    fun reset() {
        assertInitialized("reset") ?: return
        scope.launch {
            queueManager.flush()
            identity.reset()
            sessionManager.startNewSession()
        }
    }

    /**
     * Sets profile attributes for the current user (e.g. name, email, plan).
     */
    @JvmStatic
    fun peopleSet(properties: Map<String, Any>) {
        assertInitialized("peopleSet") ?: return
        scope.launch {
            val event = mapOf(
                "type" to "people",
                "distinct_id" to identity.distinctId,
                "timestamp" to currentIsoTimestamp(),
                "properties" to (properties + mapOf(
                    "\$session_id" to sessionManager.sessionId,
                    "\$screen_name" to currentScreen
                )),
                "default_properties" to defaultProperties
            )
            queueManager.enqueue(event)
            queueManager.flush()
        }
    }

    /**
     * Convenience wrapper for common user traits.
     */
    @JvmStatic
    @JvmOverloads
    fun setPerson(
        name: String? = null,
        email: String? = null,
        avatar: String? = null,
        properties: Map<String, Any> = emptyMap(),
    ) {
        val traits = buildMap<String, Any> {
            name?.let { put("\$name", it) }
            email?.let { put("\$email", it) }
            avatar?.let { put("\$avatar", it) }
            putAll(properties)
        }
        if (traits.isNotEmpty()) peopleSet(traits)
    }

    /**
     * Forces an immediate upload of all queued events.
     */
    @JvmStatic
    fun flush() {
        assertInitialized("flush") ?: return
        scope.launch { queueManager.flush() }
    }

    /**
     * Tears down the SDK. Only needed in unusual cases (e.g. instrumentation tests).
     */
    @JvmStatic
    fun shutdown() {
        if (!isInitialized) return
        lifecycleObserver.unregister()
        replayRecorder.destroy()
        isInitialized = false
        logger.debug("🔌 Sankofa shut down")
    }

    // --- Internal ---

    private suspend fun onNewSessionStarted(apiKey: String, base: String, sessionId: String) {
        // ── Unified Handshake ──
        // One call to /api/v1/handshake returns the config for ALL
        // Sankofa products. We extract the replay module config here
        // and store the full response for Deploy and future modules.
        val handshake = fetchHandshake(apiKey, base)

        // ── Replay Module ──
        if (config.recordSessions) {
            val replayModule = handshake?.get("replay")
            replayConfig = if (replayModule != null) {
                @Suppress("UNCHECKED_CAST")
                ReplayConfig.fromJson(replayModule as Map<*, *>)
            } else {
                ReplayConfig.defaults()
            }
            val rc = replayConfig!!

            if (!rc.enabled) {
                logger.debug("⏸ Replay disabled by server config")
            } else if (Random.nextDouble() > rc.sampleRate) {
                logger.debug("🎲 Session sampled out (rate=${rc.sampleRate})")
            } else {
                replayUploader.configure(
                    sessionId = sessionId,
                    distinctId = identity.distinctId,
                )
                logger.debug("🎥 Replay configured for session $sessionId")
            }
        }

        // ── Analytics Module ──
        val analyticsModule = handshake?.get("analytics")
        if (analyticsModule != null) {
            @Suppress("UNCHECKED_CAST")
            val am = analyticsModule as? Map<*, *>
            val analyticsEnabled = am?.get("enabled") as? Boolean ?: true
            if (!analyticsEnabled) {
                logger.debug("⏸ Analytics disabled by server config — events will be silently dropped")
            }
        }

        // ── Deploy Module ──
        // The deploy update check result is embedded in the handshake
        // response. The React Native JS layer reads it; the native
        // Android SDK logs it for observability but doesn't act on it
        // (native apps can't do JS OTA). Future: pass to a Deploy
        // module if one is registered.
        val deployModule = handshake?.get("deploy")
        if (deployModule != null) {
            @Suppress("UNCHECKED_CAST")
            val dm = deployModule as? Map<*, *>
            val hasUpdate = dm?.get("has_update") as? Boolean ?: false
            if (hasUpdate) {
                val label = dm?.get("label") as? String ?: "unknown"
                logger.debug("📦 Deploy update available: $label")
            }
        }
    }

    /**
     * Unified handshake — fetches the full module config for all
     * Sankofa products in a single HTTP call.
     *
     * Returns the `modules` map from the response, or null on failure.
     * Falls back to the legacy `/api/replay/config` endpoint if the
     * handshake endpoint is not available (older server versions).
     */
    @Suppress("UNCHECKED_CAST")
    private fun fetchHandshake(apiKey: String, base: String): Map<String, Any?>? {
        return try {
            val okRequest = okhttp3.Request.Builder()
                .url("$base/api/v1/handshake")
                .addHeader("x-api-key", apiKey)
                .build()
            val client = okhttp3.OkHttpClient()
            val response = client.newCall(okRequest).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val json = gson.fromJson(body, Map::class.java) as? Map<String, Any?> ?: return null
                logger.debug("🤝 Handshake OK (project=${json["project_id"]})")
                json["modules"] as? Map<String, Any?>
            } else {
                // Fallback: try legacy replay config endpoint
                logger.debug("🤝 Handshake not available (${response.code}), falling back to legacy config")
                fetchLegacyReplayConfig(apiKey, base)?.let { rc ->
                    mapOf("replay" to rc)
                }
            }
        } catch (e: Exception) {
            logger.warn("⚠️ Handshake failed: ${e.message}")
            null
        }
    }

    /** Legacy fallback for servers that don't have the handshake endpoint yet. */
    @Suppress("UNCHECKED_CAST")
    private fun fetchLegacyReplayConfig(apiKey: String, base: String): Map<*, *>? {
        return try {
            val okRequest = okhttp3.Request.Builder()
                .url("$base/api/replay/config")
                .addHeader("x-api-key", apiKey)
                .build()
            val response = okhttp3.OkHttpClient().newCall(okRequest).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                gson.fromJson(body, Map::class.java) as? Map<*, *>
            } else null
        } catch (e: Exception) {
            logger.warn("⚠️ Legacy replay config fetch failed: ${e.message}")
            null
        }
    }

    private fun assertInitialized(method: String): Unit? {
        return if (!isInitialized) {
            logger.warn("❌ Sankofa.$method() called before init()")
            null
        } else Unit
    }

    private fun handleServerCommands(commands: List<SankofaCommand>) {
        commands.forEach { cmd ->
            if (cmd.type == "CAPTURE_PRISTINE") {
                val screen = cmd.params?.get("screen") as? String
                if (screen != null) {
                    logger.debug("🔥 📸 Server requested pristine capture for $screen")
                    replayRecorder.triggerHighFidelityMode(1000L)
                }
            }
        }
    }
}
