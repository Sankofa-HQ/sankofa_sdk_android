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
import dev.sankofa.sdk.replay.BitmapPool
import dev.sankofa.sdk.replay.ReplayConfig
import dev.sankofa.sdk.replay.ReplayRecorder
import dev.sankofa.sdk.replay.ReplayUploader
import dev.sankofa.sdk.util.SankofaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
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

    private val scope = CoroutineScope(Dispatchers.IO)
    private val gson = Gson()

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
            if (config.trackLifecycleEvents) {
                track("\$app_opened")
            }
            isInitialized = true
            logger.debug("⚡ Sankofa initialized (debug=${config.debug})")
        }

        lifecycleObserver.register()
    }

    // --- Public API ---

    /**
     * Tracks a custom event with optional [properties].
     * This is a fire-and-forget call – it returns immediately and writes to the
     * local Room DB on a background thread.
     */
    @JvmStatic
    @JvmOverloads
    fun track(eventName: String, properties: Map<String, Any> = emptyMap()) {
        if (!isInitialized && eventName != "\$app_opened") {
            logger.warn("❌ Sankofa.track() called before init()")
            return
        }

        scope.launch {
            sessionManager.refresh()

            val event = buildMap<String, Any> {
                put("event", eventName)
                put("distinct_id", identity.distinctId)
                put("session_id", sessionManager.sessionId)
                put("timestamp", System.currentTimeMillis())
                put("message_id", UUID.randomUUID().toString())
                putAll(defaultProperties)
                putAll(properties)
            }

            queueManager.enqueue(event)
            logger.debug("📝 Tracked: $eventName")

            // High-fidelity trigger check
            val rc = replayConfig
            if (rc != null && rc.highFidelityTriggers.contains(eventName)) {
                logger.debug("🚀 High-fidelity trigger: $eventName")
                // Future: switch replay to high-fidelity mode for rc.highFidelityDurationSeconds
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
            val aliasEvent = identity.identify(userId) ?: return@launch
            queueManager.enqueue(aliasEvent)
            queueManager.flush()
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
                "timestamp" to System.currentTimeMillis(),
                "\$set" to properties,
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
        isInitialized = false
        logger.debug("🔌 Sankofa shut down")
    }

    // --- Internal ---

    private suspend fun onNewSessionStarted(apiKey: String, base: String, sessionId: String) {
        if (!config.recordSessions) return

        // Fetch server-driven replay config
        replayConfig = fetchReplayConfig(apiKey, base) ?: ReplayConfig.defaults()
        val rc = replayConfig!!

        if (!rc.enabled) {
            logger.debug("⏸ Replay disabled by server config")
            return
        }

        // Client-side sampling
        if (Random.nextDouble() > rc.sampleRate) {
            logger.debug("🎲 Session sampled out (rate=${rc.sampleRate})")
            return
        }

        replayUploader.configure(
            sessionId = sessionId,
            distinctId = identity.distinctId,
        )
        logger.debug("🎥 Replay configured for session $sessionId")
    }

    private fun fetchReplayConfig(apiKey: String, base: String): ReplayConfig? {
        return try {
            val okRequest = okhttp3.Request.Builder()
                .url("$base/api/replay/config")
                .addHeader("x-api-key", apiKey)
                .build()
            val client = okhttp3.OkHttpClient()
            val response = client.newCall(okRequest).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(body, Map::class.java) as Map<*, *>
                ReplayConfig.fromJson(json)
            } else null
        } catch (e: Exception) {
            logger.warn("⚠️ Failed to fetch replay config: ${e.message}")
            null
        }
    }

    private fun assertInitialized(method: String): Unit? {
        return if (!isInitialized) {
            logger.warn("❌ Sankofa.$method() called before init()")
            null
        } else Unit
    }
}
