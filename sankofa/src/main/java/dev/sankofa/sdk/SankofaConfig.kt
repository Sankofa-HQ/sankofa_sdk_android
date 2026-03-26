package dev.sankofa.sdk

/**
 * Configuration for the Sankofa SDK.
 *
 * Pass an instance to [Sankofa.init] to customize behavior.
 *
 * ```kotlin
 * Sankofa.init(
 *     context = this,
 *     apiKey = "sk_live_12345",
 *     config = SankofaConfig(
 *         recordSessions = true,
 *         maskAllInputs = true,
 *     )
 * )
 * ```
 */
data class SankofaConfig(
    /** Base URL of the Sankofa ingestion server. Defaults to the Sankofa cloud. */
    val endpoint: String = "https://api.sankofa.dev",

    /** Record user sessions as WebP frame sequences. Defaults to true. */
    val recordSessions: Boolean = true,

    /**
     * Automatically draw a black rectangle over every [android.widget.EditText].
     * Prevents passwords, credit card numbers etc. from being captured. Defaults to true.
     */
    val maskAllInputs: Boolean = true,

    /** Emit verbose SDK logs to Logcat. Set to false in production. */
    val debug: Boolean = false,

    /** Auto-track $app_opened, $app_foregrounded, $app_backgrounded. Defaults to true. */
    val trackLifecycleEvents: Boolean = true,

    /** Flush event queue every N seconds while the app is foregrounded. */
    val flushIntervalSeconds: Int = 30,

    /** Upload a batch when this many events have accumulated in the local DB. */
    val batchSize: Int = 50,
)
