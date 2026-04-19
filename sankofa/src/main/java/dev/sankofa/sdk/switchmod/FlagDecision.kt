package dev.sankofa.sdk.switchmod

// Wire shape mirroring server/engine/ee/switchmod/evaluator_batch.go.
// Keeping these colocated with the module so a server rename breaks
// the Kotlin compile — silent drift is the worst failure mode for
// feature-flag contracts.

/**
 * Reason tag returned alongside a flag decision. Stable across server
 * releases — SDKs and dashboards may key off these values. Any new
 * server reason the SDK doesn't recognise falls into [UNKNOWN] so
 * forward-compat doesn't hard-fail.
 */
enum class FlagReason(val wireName: String) {
    ARCHIVED("archived"),
    HALTED("halted"),
    SCHEDULED("scheduled"),
    NO_RULE("no_rule"),
    ROLLOUT("rollout"),
    VARIANT_ASSIGNED("variant_assigned"),
    VARIANT_UNAVAILABLE("variant_unavailable"),
    NOT_IN_ROLLOUT("not_in_rollout"),
    IN_EXCLUDED_COHORT("in_excluded_cohort"),
    NOT_IN_TARGET_COHORT("not_in_target_cohort"),
    COHORT_LOOKUP_FAILED("cohort_lookup_failed"),
    COUNTRY_BLOCKED("country_blocked"),
    COUNTRY_NOT_IN_ALLOW("country_not_in_allow"),
    COUNTRY_UNKNOWN("country_unknown"),
    APP_VERSION_BELOW_MIN("app_version_below_min"),
    APP_VERSION_ABOVE_MAX("app_version_above_max"),
    OS_VERSION_BELOW_MIN("os_version_below_min"),
    OS_VERSION_ABOVE_MAX("os_version_above_max"),
    NOT_IN_USER_ALLOW_LIST("not_in_user_allow_list"),
    DEPENDENCY_UNMET("dependency_unmet"),
    OVERRIDE_PARSE_ERROR("override_parse_error"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(raw: String?): FlagReason {
            if (raw == null) return UNKNOWN
            return entries.firstOrNull { it.wireName == raw } ?: UNKNOWN
        }
    }
}

/**
 * A single flag evaluation result.
 *
 * - [value] is the boolean the SDK returns from `getFlag`. For variant
 *   flags, `value` is `true` iff the assigned variant is non-default.
 * - [variant] is the assigned variant key ("A", "control", ...) for
 *   variant flags; empty for boolean flags.
 * - [reason] is the stable tag the server emitted.
 * - [version] is the flag's current version.
 */
data class FlagDecision(
    val value: Boolean,
    val variant: String = "",
    val reason: FlagReason,
    val version: Int,
) {
    /** Serialise to the wire JSON shape used by the cache + network. */
    fun toWire(): Map<String, Any> = mapOf(
        "value" to value,
        "variant" to variant,
        "reason" to reason.wireName,
        "version" to version,
    )

    companion object {
        /** Decode from a raw handshake dictionary. Returns null on
         *  obviously-broken shapes so one bad entry doesn't poison the
         *  whole flag map. */
        @Suppress("UNCHECKED_CAST")
        fun fromWire(json: Map<String, Any?>?): FlagDecision? {
            if (json == null) return null
            val value = json["value"] as? Boolean ?: false
            val variant = json["variant"] as? String ?: ""
            val reason = FlagReason.fromWire(json["reason"] as? String)
            val version = (json["version"] as? Number)?.toInt() ?: 0
            return FlagDecision(value, variant, reason, version)
        }
    }
}

/**
 * Callback fired when a flag's decision changes after a handshake
 * refresh. `decision` is `null` when the flag was removed remotely.
 */
fun interface FlagChangeListener {
    fun onChange(decision: FlagDecision?)
}
