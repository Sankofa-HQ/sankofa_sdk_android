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

    // ── Catch (error + crash + ANR reporting) ─────────────────────────
    //
    // Crashlytics + Sentry merged: when [enableCatch] is true, [Sankofa.init]
    // installs the chained `Thread.setDefaultUncaughtExceptionHandler`, the
    // ANR watcher, and the persistent retry queue automatically — host
    // code does NOT need a separate `SankofaCatch.init(...)` call.

    /**
     * Auto-start [dev.sankofa.sdk.catchmod.SankofaCatch] inside [Sankofa.init].
     * Defaults to true. Set to false only if you need to defer Catch
     * boot (e.g. integration tests that spy on the global handler).
     */
    val enableCatch: Boolean = true,

    /** Catch environment tag — "live", "staging", "dev", custom. */
    val catchEnvironment: String = "live",

    /** Optional release identifier (e.g. "myapp@1.4.0+42") sent on every Catch event. */
    val release: String? = null,

    /** Optional app version string sent in the Catch device context. */
    val appVersion: String? = null,

    /**
     * Synchronous hook called AFTER an event has been composed but
     * BEFORE it's enqueued. Return the (possibly modified) event to
     * ship it; return `null` to drop it entirely. Use for PII
     * scrubbing, noise filtering, or late tag enrichment.
     *
     * Throws are swallowed — a host hook can never block the capture
     * pipeline.
     */
    val beforeSend: dev.sankofa.sdk.catchmod.BeforeSendFn? = null,
)
