package dev.sankofa.sdk.pulse.targeting

import com.google.gson.annotations.SerializedName

/**
 * Targeting wire-shape types. Mirrors the server's
 * `server/engine/ee/pulse/targeting/types.go` and the Web SDK's
 * `targeting.ts` types. Keep this file in lockstep with both —
 * drift here is exactly the bug the cross-language tests are
 * designed to catch.
 */

/** Closed allowlist of rule kinds. */
internal object PulseRuleKind {
    const val URL = "url"
    const val EVENT = "event"
    const val USER_PROPERTY = "user_property"
    const val COHORT = "cohort"
    const val SAMPLING = "sampling"
    const val FREQUENCY_CAP = "frequency_cap"
    const val FEATURE_FLAG = "feature_flag"
}

/** Closed allowlist of match operations. */
internal object PulseMatchOp {
    const val EQUALS = "equals"
    const val NOT_EQUALS = "not_equals"
    const val CONTAINS = "contains"
    const val NOT_CONTAINS = "not_contains"
    const val PREFIX = "prefix"
    const val REGEX = "regex"
    const val IN = "in"
    const val NOT_IN = "not_in"
    const val EXISTS = "exists"
    const val NOT_EXISTS = "not_exists"
    const val GT = "gt"
    const val LT = "lt"
    const val GTE = "gte"
    const val LTE = "lte"
}

/**
 * One targeting rule. Loosely-typed by design — a rule's relevant
 * fields depend on its `kind`, and Gson cleanly tolerates absent
 * fields without forcing a sealed-class hierarchy.
 */
internal data class PulseTargetingRule(
    @SerializedName("kind") val kind: String,
    // url
    @SerializedName("url_match") val urlMatch: String? = null,
    @SerializedName("url_value") val urlValue: String? = null,
    // event
    @SerializedName("event_name") val eventName: String? = null,
    @SerializedName("event_min_count") val eventMinCount: Int? = null,
    @SerializedName("event_window_days") val eventWindowDays: Int? = null,
    // user_property
    @SerializedName("property_key") val propertyKey: String? = null,
    @SerializedName("property_op") val propertyOp: String? = null,
    @SerializedName("property_value") val propertyValue: Any? = null,
    // cohort
    @SerializedName("cohort_id") val cohortId: String? = null,
    // sampling
    @SerializedName("sampling_rate") val samplingRate: Double? = null,
    // frequency_cap
    @SerializedName("frequency_scope") val frequencyScope: String? = null,
    @SerializedName("frequency_max") val frequencyMax: Int? = null,
    @SerializedName("frequency_window_days") val frequencyWindowDays: Int? = null,
    // feature_flag
    @SerializedName("flag_key") val flagKey: String? = null,
    @SerializedName("flag_value") val flagValue: Any? = null,
)

/**
 * Full eligibility context — mirrors server `EligibilityContext`.
 * Every field nullable so the host can build a partial context for
 * one specific rule kind without fabricating empty maps for the
 * others.
 */
data class PulseEligibilityContext(
    val surveyId: String,
    val respondentExternalId: String,
    val pageUrl: String? = null,
    val recentEvents: Map<String, Int>? = null,
    val userProperties: Map<String, Any?>? = null,
    val cohorts: Map<String, Boolean>? = null,
    val flagValues: Map<String, Any?>? = null,
    val priorResponseCount: Map<String, Int>? = null,
)

/** Result of an eligibility evaluation. */
data class PulseDecision(
    val eligible: Boolean,
    val reason: String? = null,
)
