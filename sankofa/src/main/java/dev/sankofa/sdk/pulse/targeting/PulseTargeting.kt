package dev.sankofa.sdk.pulse.targeting

import java.security.MessageDigest

/**
 * Targeting evaluator — Kotlin port of
 * `sdks/sankofa_sdk_web/packages/pulse/src/targeting.ts` and
 * `server/engine/ee/pulse/targeting/evaluator.go`.
 *
 * Behavioural contract: every (rules, ctx) pair MUST produce the
 * same Decision the Web + Go evaluators produce. The parity test
 * suite at `dev/sankofa/sdk/pulse/targeting/PulseTargetingTest.kt`
 * mirrors the Web tests verbatim; both are themselves ports of the
 * Go test cases. Sampling decisions in particular MUST agree
 * across server + client — a server-says-in / client-says-out split
 * produces zero exposure rows and breaks A/B reasoning.
 */
internal object PulseTargeting {

    fun evaluate(
        rules: List<PulseTargetingRule>,
        ctx: PulseEligibilityContext,
    ): PulseDecision {
        rules.forEachIndexed { i, rule ->
            val (ok, reason) = evaluateOne(rule, ctx)
            if (!ok) {
                return PulseDecision(
                    eligible = false,
                    reason = "rule[$i] ${rule.kind}: $reason",
                )
            }
        }
        return PulseDecision(eligible = true)
    }

    private fun evaluateOne(
        rule: PulseTargetingRule,
        ctx: PulseEligibilityContext,
    ): Pair<Boolean, String> = when (rule.kind) {
        PulseRuleKind.URL -> evalUrl(rule, ctx)
        PulseRuleKind.EVENT -> evalEvent(rule, ctx)
        PulseRuleKind.USER_PROPERTY -> evalUserProperty(rule, ctx)
        PulseRuleKind.COHORT -> evalCohort(rule, ctx)
        PulseRuleKind.SAMPLING -> evalSampling(rule, ctx)
        PulseRuleKind.FREQUENCY_CAP -> evalFrequencyCap(rule, ctx)
        PulseRuleKind.FEATURE_FLAG -> evalFeatureFlag(rule, ctx)
        else -> false to "unknown rule kind"
    }

    // ── URL ──────────────────────────────────────────────────────────

    private fun evalUrl(
        rule: PulseTargetingRule,
        ctx: PulseEligibilityContext,
    ): Pair<Boolean, String> {
        val url = ctx.pageUrl ?: ""
        val target = rule.urlValue ?: ""
        return when (rule.urlMatch) {
            PulseMatchOp.EQUALS ->
                if (url == target) true to "" else false to "url not equal to target"
            PulseMatchOp.CONTAINS ->
                if (url.contains(target)) true to "" else false to "url does not contain target"
            PulseMatchOp.PREFIX ->
                if (url.startsWith(target)) true to "" else false to "url does not start with target"
            PulseMatchOp.REGEX -> {
                val re = try { Regex(target) } catch (_: Throwable) {
                    return false to "url regex did not compile"
                }
                if (re.containsMatchIn(url)) true to "" else false to "url does not match regex"
            }
            else -> false to "url_match unknown"
        }
    }

    // ── Event ────────────────────────────────────────────────────────

    private fun evalEvent(
        rule: PulseTargetingRule,
        ctx: PulseEligibilityContext,
    ): Pair<Boolean, String> {
        val min = rule.eventMinCount?.takeIf { it >= 1 } ?: 1
        val events = ctx.recentEvents ?: emptyMap()
        val count = events[rule.eventName ?: ""] ?: 0
        return if (count >= min) true to ""
        else false to "event \"${rule.eventName}\" seen $count times, need $min"
    }

    // ── User Property ────────────────────────────────────────────────

    private fun evalUserProperty(
        rule: PulseTargetingRule,
        ctx: PulseEligibilityContext,
    ): Pair<Boolean, String> {
        val key = rule.propertyKey ?: ""
        val props = ctx.userProperties ?: emptyMap()
        val present = props.containsKey(key)
        val v = props[key]

        when (rule.propertyOp) {
            PulseMatchOp.EXISTS ->
                return if (present) true to "" else false to "property absent"
            PulseMatchOp.NOT_EXISTS ->
                return if (!present) true to "" else false to "property present"
        }

        if (!present) return false to "property absent"
        val target = rule.propertyValue
        return when (rule.propertyOp) {
            PulseMatchOp.EQUALS ->
                if (jsonEqual(v, target)) true to "" else false to "property not equal"
            PulseMatchOp.NOT_EQUALS ->
                if (!jsonEqual(v, target)) true to "" else false to "property equal"
            PulseMatchOp.CONTAINS ->
                if (strContains(v, target)) true to ""
                else false to "property does not contain target"
            PulseMatchOp.NOT_CONTAINS ->
                if (!strContains(v, target)) true to ""
                else false to "property contains target"
            PulseMatchOp.IN ->
                if (jsonInArray(v, target)) true to ""
                else false to "property not in target list"
            PulseMatchOp.NOT_IN ->
                if (!jsonInArray(v, target)) true to ""
                else false to "property in target list"
            PulseMatchOp.GT, PulseMatchOp.LT, PulseMatchOp.GTE, PulseMatchOp.LTE ->
                compareNumbers(v, target, rule.propertyOp)
            else -> false to "property_op unknown"
        }
    }

    // ── Cohort ───────────────────────────────────────────────────────

    private fun evalCohort(
        rule: PulseTargetingRule,
        ctx: PulseEligibilityContext,
    ): Pair<Boolean, String> {
        val id = rule.cohortId ?: ""
        val cohorts = ctx.cohorts ?: emptyMap()
        return if (cohorts[id] == true) true to ""
        else false to "respondent not in cohort \"$id\""
    }

    // ── Sampling ─────────────────────────────────────────────────────

    /**
     * Deterministic per-user sampling. Hashes
     * `survey_id + ":" + respondent_external_id` to a 64-bit unsigned
     * integer + maps to [0, 1). Critical: this MUST produce the same
     * value the Web + Go evaluators produce for the same input —
     * otherwise the same user lands in the cohort on Web and out of
     * the cohort on Android, and exposure rows go missing.
     */
    private fun evalSampling(
        rule: PulseTargetingRule,
        ctx: PulseEligibilityContext,
    ): Pair<Boolean, String> {
        val rate = rule.samplingRate ?: 0.0
        if (rate <= 0.0) return false to "sampling rate is 0"
        if (rate >= 1.0) return true to ""
        if (ctx.respondentExternalId.isEmpty()) {
            return false to "no external_id; cannot sample deterministically"
        }
        val score = stableHash("${ctx.surveyId}:${ctx.respondentExternalId}")
        return if (score < rate) true to ""
        else false to "sampling miss (score=${"%.3f".format(score)}, rate=${"%.3f".format(rate)})"
    }

    /**
     * Synchronous SHA-256 over a UTF-8 string, returning the top 64
     * bits as a value in [0, 1). Same construction the Go + Web
     * sides use — top 4 bytes form `hi`, next 4 form `lo`, then
     * `hi / 2^32 + lo / 2^64`.
     */
    fun stableHash(input: String): Double {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        // First 8 bytes interpreted as two unsigned 32-bit ints, big-endian.
        val hi = ((bytes[0].toLong() and 0xFF) shl 24) or
            ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or
            (bytes[3].toLong() and 0xFF)
        val lo = ((bytes[4].toLong() and 0xFF) shl 24) or
            ((bytes[5].toLong() and 0xFF) shl 16) or
            ((bytes[6].toLong() and 0xFF) shl 8) or
            (bytes[7].toLong() and 0xFF)
        return hi.toDouble() / 4294967296.0 + lo.toDouble() / 18446744073709552000.0
    }

    // ── Frequency cap ────────────────────────────────────────────────

    private fun evalFrequencyCap(
        rule: PulseTargetingRule,
        ctx: PulseEligibilityContext,
    ): Pair<Boolean, String> {
        val max = rule.frequencyMax?.takeIf { it >= 1 } ?: 1
        val prior = ctx.priorResponseCount?.get(ctx.surveyId) ?: 0
        return if (prior < max) true to ""
        else false to "respondent has $prior prior responses (cap=$max, window=${rule.frequencyWindowDays}d)"
    }

    // ── Feature flag ─────────────────────────────────────────────────

    private fun evalFeatureFlag(
        rule: PulseTargetingRule,
        ctx: PulseEligibilityContext,
    ): Pair<Boolean, String> {
        val key = rule.flagKey ?: ""
        val flags = ctx.flagValues ?: emptyMap()
        if (!flags.containsKey(key)) return false to "flag \"$key\" not in context"
        val have = flags[key]
        return if (jsonEqual(have, rule.flagValue)) true to ""
        else false to "flag \"$key\" value $have != ${rule.flagValue}"
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun jsonEqual(a: Any?, b: Any?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        if (a::class != b::class) {
            // Mirror the Web port's "different types ⇒ not equal" rule.
            return false
        }
        return a == b
    }

    private fun strContains(v: Any?, target: Any?): Boolean {
        val haystack = if (v is String) v else v?.toString() ?: ""
        val needle = if (target is String) target else target?.toString() ?: ""
        return haystack.contains(needle)
    }

    private fun jsonInArray(v: Any?, target: Any?): Boolean {
        val list = target as? List<*> ?: return false
        return list.any { jsonEqual(v, it) }
    }

    private fun compareNumbers(
        v: Any?,
        target: Any?,
        op: String,
    ): Pair<Boolean, String> {
        val left = numericValue(v) ?: return false to "property is not numeric"
        val right = numericValue(target) ?: return false to "target is not numeric"
        return when (op) {
            PulseMatchOp.GT ->
                if (left > right) true to "" else false to "$left $op $right fails"
            PulseMatchOp.LT ->
                if (left < right) true to "" else false to "$left $op $right fails"
            PulseMatchOp.GTE ->
                if (left >= right) true to "" else false to "$left $op $right fails"
            PulseMatchOp.LTE ->
                if (left <= right) true to "" else false to "$left $op $right fails"
            else -> false to "op $op not numeric"
        }
    }

    private fun numericValue(v: Any?): Double? = when (v) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is String -> v.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }
        else -> null
    }
}
