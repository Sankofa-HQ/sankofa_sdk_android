package dev.sankofa.sdk.pulse

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import dev.sankofa.sdk.Sankofa
import dev.sankofa.sdk.core.SankofaModuleName
import dev.sankofa.sdk.core.SankofaModuleRegistry
import dev.sankofa.sdk.core.SankofaPluggableModule
import dev.sankofa.sdk.pulse.targeting.PulseDecision
import dev.sankofa.sdk.pulse.targeting.PulseEligibilityContext
import dev.sankofa.sdk.pulse.targeting.PulseTargeting
import dev.sankofa.sdk.pulse.targeting.PulseTargetingRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Sankofa Pulse — in-app surveys on Android.
 *
 * ## Quick Start
 * ```kotlin
 * Sankofa.init(this, apiKey = "sk_live_…")
 * SankofaPulse.register(applicationContext)
 *
 * // Programmatic show
 * SankofaPulse.show(surveyId = "psv_abc", activity = this)
 *
 * // Or fetch what's eligible right now and pick one yourself
 * SankofaPulse.activeMatchingSurveys { surveys -> /* … */ }
 * ```
 *
 * Module shape mirrors the iOS `SankofaPulse.shared` and Web
 * `Sankofa.pulse.*` so a host app jumping platforms doesn't relearn
 * the surface. Self-registers with the Traffic Cop on [register] so
 * the handshake response's `modules.pulse` payload flows through
 * [applyHandshake]; survey *content* still comes from the dedicated
 * `/api/pulse/handshake` because the unified handshake only carries
 * enable/disable + tier gating, not the survey graph.
 */
object SankofaPulse : SankofaPluggableModule {

    private const val TAG = "SankofaPulse"

    override val canonicalName: SankofaModuleName = SankofaModuleName.PULSE

    private val stateLock = Any()
    @Volatile private var registered: Boolean = false
    @Volatile private var enabled: Boolean = true
    @Volatile private var client: PulseClient? = null
    @Volatile private var queue: PulseQueue? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var cachedSurveys: List<PulseSurvey> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var refreshJob: Job? = null

    // ── Public lifecycle ─────────────────────────────────────────────

    /**
     * Wires Pulse to the host's already-initialised Sankofa SDK.
     * Idempotent — calling twice is a no-op. Returns false if the
     * host hasn't called [Sankofa.init] yet (no API key/endpoint to
     * read).
     */
    @JvmStatic
    fun register(context: Context): Boolean {
        synchronized(stateLock) {
            if (registered) return true
            val apiKey = Sankofa.apiKey()
            val endpoint = Sankofa.endpoint()
            if (apiKey.isNullOrEmpty() || endpoint.isNullOrEmpty()) {
                Log.w(TAG, "register() called before Sankofa.init() — skipping")
                return false
            }
            this.appContext = context.applicationContext
            this.client = PulseClient(endpoint = endpoint, apiKey = apiKey)
            this.queue = PulseQueue(context.applicationContext)
            this.registered = true
        }
        SankofaModuleRegistry.register(this)
        // First refresh fires off-thread so the host's onCreate() returns
        // immediately. Subsequent refreshes are driven by the Traffic Cop
        // every time a fresh handshake lands.
        refreshSurveysAsync()
        return true
    }

    /**
     * Returns true once [register] has been called and we have a
     * working client.
     */
    @JvmStatic
    fun isRegistered(): Boolean = registered

    // ── SankofaPluggableModule ───────────────────────────────────────

    override suspend fun applyHandshake(config: Map<String, Any?>) {
        // The Traffic Cop only invokes this when `enabled: true`, but
        // we still respect the flag in case the dashboard turns Pulse
        // off mid-session.
        val on = config["enabled"] as? Boolean ?: true
        enabled = on
        if (!on) return

        // The unified handshake may inline a partial survey list (small
        // payload optimisation). Take it if present so the very first
        // show() call doesn't have to wait on a second round-trip; the
        // dedicated /api/pulse/handshake refresh still runs to pick up
        // anything the unified payload elided.
        @Suppress("UNCHECKED_CAST")
        val inlineSurveys = config["surveys"] as? List<Map<String, Any?>>
        if (!inlineSurveys.isNullOrEmpty()) {
            try {
                val gson = com.google.gson.Gson()
                val asJson = gson.toJson(inlineSurveys)
                val list = gson.fromJson(
                    asJson,
                    com.google.gson.reflect.TypeToken
                        .getParameterized(List::class.java, PulseSurvey::class.java).type,
                ) as? List<PulseSurvey>
                if (list != null) cachedSurveys = list
            } catch (_: Throwable) {
                // Inline list malformed — fall through to the dedicated
                // handshake call below.
            }
        }
        refreshSurveysAsync()
    }

    // ── Public reads ─────────────────────────────────────────────────

    /**
     * Returns the surveys eligible for the current user/session.
     * v1 is "every published survey from the handshake"; targeting
     * evaluation lands in a future release. The callback fires on
     * the main thread.
     */
    @JvmStatic
    fun activeMatchingSurveys(callback: (List<PulseSurvey>) -> Unit) {
        val current = cachedSurveys
        if (current.isNotEmpty() || refreshJob == null) {
            postToMain { callback(current) }
            return
        }
        scope.launch {
            // Wait for any in-flight refresh to settle so the caller
            // doesn't get an empty list when one is about to land.
            refreshJob?.join()
            val list = cachedSurveys
            postToMain { callback(list) }
        }
    }

    /**
     * Forces a refresh of the cached survey list from the server.
     * Returns immediately; the result lands in [activeMatchingSurveys]
     * on the next call. Useful right after identify().
     */
    @JvmStatic
    fun refreshSurveys() { refreshSurveysAsync() }

    // ── Programmatic presentation ────────────────────────────────────

    /**
     * Show a survey by id. Fetches the full bundle, runs targeting
     * locally; if the respondent isn't eligible we silently skip
     * (the host can call [isEligible] up-front to decide on its own
     * what to do with a 'no').
     *
     * [properties] populates `userProperties` for `user_property`
     * rules; [flags] populates `flagValues` for `feature_flag` rules.
     * Other context fields auto-fill from Sankofa core (identity,
     * session) or are left empty.
     *
     * Must be called from the UI thread (Activity callbacks always
     * are).
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        surveyId: String,
        activity: Activity,
        properties: Map<String, Any?> = emptyMap(),
        flags: Map<String, Any?> = emptyMap(),
    ) {
        if (!registered) {
            Log.w(TAG, "show() called before register() — skipping")
            return
        }
        if (!enabled) return
        val c = client ?: return
        scope.launch {
            val bundle = try {
                c.loadSurveyBundle(surveyId)
            } catch (e: Throwable) {
                Log.w(TAG, "show($surveyId) — bundle fetch failed: ${e.message}")
                return@launch
            }
            if (bundle.survey.id.isEmpty()) {
                Log.w(TAG, "show($surveyId) — survey not found")
                return@launch
            }
            val decision = evaluateLocally(
                surveyId = surveyId,
                rules = bundle.targetingRules,
                properties = properties,
                flags = flags,
            )
            if (!decision.eligible) {
                Log.d(TAG, "show($surveyId) — ineligible: ${decision.reason}")
                return@launch
            }
            // Hydrate from any in-progress partial. We swallow load
            // failures (offline, expired, server error) — the survey
            // simply starts fresh, which is strictly better than
            // refusing to show.
            val externalId = Sankofa.distinctId().orEmpty()
            val partial = if (externalId.isNotEmpty()) {
                runCatching { c.loadPartial(surveyId, externalId) }.getOrNull()
            } else null
            postToMain {
                if (!activity.isFinishing) {
                    present(
                        survey = bundle.survey,
                        branchingRules = bundle.branchingRules,
                        initialAnswers = partial?.answers ?: emptyMap(),
                        initialQuestionId = partial?.currentQuestionId,
                        activity = activity,
                    )
                }
            }
        }
    }

    /**
     * Returns the targeting Decision for [surveyId] without showing.
     * Suspends because it has to fetch the bundle. Useful for hosts
     * that want to render their own UI affordance ("answer a quick
     * survey?") only if the survey is currently eligible.
     */
    suspend fun isEligible(
        surveyId: String,
        properties: Map<String, Any?> = emptyMap(),
        flags: Map<String, Any?> = emptyMap(),
    ): PulseDecision {
        if (!registered) return PulseDecision(false, "pulse not registered")
        if (!enabled) return PulseDecision(false, "pulse disabled by handshake")
        val c = client ?: return PulseDecision(false, "no client")
        val bundle = withContext(Dispatchers.IO) {
            try { c.loadSurveyBundle(surveyId) } catch (_: Throwable) { null }
        } ?: return PulseDecision(false, "bundle fetch failed")
        if (bundle.survey.id.isEmpty()) return PulseDecision(false, "survey not found")
        return evaluateLocally(
            surveyId = surveyId,
            rules = bundle.targetingRules,
            properties = properties,
            flags = flags,
        )
    }

    private fun evaluateLocally(
        surveyId: String,
        rules: List<PulseTargetingRule>,
        properties: Map<String, Any?>,
        flags: Map<String, Any?>,
    ): PulseDecision {
        if (rules.isEmpty()) return PulseDecision(eligible = true)
        val ctx = PulseEligibilityContext(
            surveyId = surveyId,
            respondentExternalId = Sankofa.distinctId().orEmpty(),
            userProperties = properties,
            flagValues = flags,
        )
        return PulseTargeting.evaluate(rules, ctx)
    }

    private fun present(
        survey: PulseSurvey,
        branchingRules: List<dev.sankofa.sdk.pulse.branching.PulseBranchingRule> = emptyList(),
        initialAnswers: Map<String, Any?> = emptyMap(),
        initialQuestionId: String? = null,
        activity: Activity,
    ) {
        val externalId = Sankofa.distinctId().orEmpty()
        val dialog = PulseSurveyDialog(
            context = activity,
            survey = survey,
            branchingRules = branchingRules,
            initialAnswers = initialAnswers,
            initialQuestionId = initialQuestionId,
            onProgress = { answers, currentQuestionId ->
                if (externalId.isNotEmpty()) {
                    schedulePartialSave(
                        surveyId = survey.id,
                        externalId = externalId,
                        answers = answers,
                        currentQuestionId = currentQuestionId,
                    )
                }
            },
            onSubmit = { payload ->
                // Submit takes care of itself; the server auto-deletes
                // the partial on a successful insert. We still try a
                // best-effort client-side delete so dismissed-then-
                // resumed-in-a-different-session doesn't surface a
                // stale partial during the brief window.
                handleSubmit(enrichContext(payload))
                if (externalId.isNotEmpty()) {
                    deletePartialAsync(survey.id, externalId)
                }
            },
            onDismiss = { /* keep partial intact for resume */ },
        )
        dialog.show()
    }

    // ── Partial save scheduler ──────────────────────────────────────
    //
    // We coalesce saves on a 750ms debounce: a fast-clicking respondent
    // who skips through 5 questions in a second only burns one save call,
    // and the latest pending state always wins. Cancel any in-flight
    // save when a new one comes in — old state is strictly stale.

    private val partialDebounceMs: Long = 750L
    @Volatile private var partialSaveJob: Job? = null

    private fun schedulePartialSave(
        surveyId: String,
        externalId: String,
        answers: Map<String, Any?>,
        currentQuestionId: String,
    ) {
        partialSaveJob?.cancel()
        partialSaveJob = scope.launch {
            try {
                kotlinx.coroutines.delay(partialDebounceMs)
                val c = client ?: return@launch
                val payload = PulsePartialUpsert(
                    surveyId = surveyId,
                    respondent = PulseRespondent(externalId = externalId),
                    context = buildPulseContext(),
                    answers = answers,
                    currentQuestionId = currentQuestionId,
                )
                c.savePartial(payload)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Replaced by a newer save — expected.
            } catch (e: Throwable) {
                Log.d(TAG, "partial save failed: ${e.message}")
            }
        }
    }

    private fun deletePartialAsync(surveyId: String, externalId: String) {
        scope.launch {
            try {
                client?.deletePartial(surveyId, externalId)
            } catch (_: Throwable) {
                // Server auto-cleans on submit anyway; ignore.
            }
        }
    }

    private fun buildPulseContext(): PulseContext = PulseContext(
        sessionId = Sankofa.currentSessionId(),
        anonymousId = Sankofa.anonymousId(),
        platform = "android",
        osVersion = "Android ${Build.VERSION.RELEASE ?: ""}".trim(),
        appVersion = appVersionString(),
        locale = Locale.getDefault().toString().ifEmpty { null },
    )

    // ── Submission ───────────────────────────────────────────────────

    private fun handleSubmit(payload: PulseSubmitPayload) {
        val c = client ?: return
        val q = queue
        scope.launch {
            try {
                c.submit(payload)
                // Any prior failures? Drain them now while the network
                // is clearly up.
                q?.let { drainQueueLocked(it, c) }
            } catch (_: Throwable) {
                // Network down — persist for next drain.
                q?.enqueue(payload)
            }
        }
    }

    private fun enrichContext(payload: PulseSubmitPayload): PulseSubmitPayload {
        val distinct = Sankofa.distinctId()?.takeIf { it.isNotEmpty() }
        val respondent = payload.respondent.copy(
            externalId = payload.respondent.externalId ?: distinct,
        )
        return payload.copy(
            respondent = respondent,
            context = buildPulseContext(),
        )
    }

    // ── Internals ────────────────────────────────────────────────────

    private fun refreshSurveysAsync() {
        val c = client ?: return
        // Replace any in-flight refresh — we always want the latest.
        refreshJob?.cancel()
        refreshJob = scope.launch {
            try {
                val resp = c.handshake()
                cachedSurveys = resp.surveys
                queue?.let { drainQueueLocked(it, c) }
            } catch (e: Throwable) {
                // First-launch handshake failures are common (offline,
                // proxy, captive portal) — log at debug, retry on the
                // next tick.
                Log.d(TAG, "handshake failed: ${e.message}")
            }
        }
    }

    private suspend fun drainQueueLocked(q: PulseQueue, c: PulseClient) {
        if (q.count() == 0) return
        withContext(Dispatchers.IO) {
            q.drain { c.submit(it) }
        }
    }

    private fun appVersionString(): String? {
        val ctx = appContext ?: return null
        return try {
            val pm = ctx.packageManager
            val info = pm.getPackageInfo(ctx.packageName, 0)
            val short = info.versionName ?: ""
            val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION") info.versionCode.toString()
            }
            when {
                short.isEmpty() && build.isEmpty() -> null
                short.isEmpty() -> build
                else -> "$short ($build)"
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun postToMain(block: () -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post(block)
    }
}
