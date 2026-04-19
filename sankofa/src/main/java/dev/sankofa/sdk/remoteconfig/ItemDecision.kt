package dev.sankofa.sdk.remoteconfig

// Wire shape mirroring server/engine/ee/configmod/evaluator_batch.go.

/**
 * Declared type of a config item. Echoed in every decision so the SDK
 * doesn't need to re-parse JSON values. Unknown types fall into
 * [UNKNOWN] for forward-compat.
 */
enum class ConfigType(val wireName: String) {
    STRING("string"),
    INT("int"),
    FLOAT("float"),
    BOOL("bool"),
    JSON("json"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(raw: String?): ConfigType {
            if (raw == null) return UNKNOWN
            return entries.firstOrNull { it.wireName == raw } ?: UNKNOWN
        }
    }
}

/**
 * Reason a config item resolved the way it did. Unknown reasons fall
 * into [UNKNOWN] for forward-compat.
 */
enum class ItemReason(val wireName: String) {
    ARCHIVED("archived"),
    NO_RULE("no_rule"),
    RULE_MATCHED("rule_matched"),
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
    UNKNOWN("unknown");

    companion object {
        fun fromWire(raw: String?): ItemReason {
            if (raw == null) return UNKNOWN
            return entries.firstOrNull { it.wireName == raw } ?: UNKNOWN
        }
    }
}

/**
 * One decision per config item. [value] is stored as [Any?] because
 * the wire format carries a polymorphic JSON primitive / Map / List.
 * Callers use [SankofaRemoteConfig.get] to pull a strongly-typed value
 * out — the module does the runtime cast.
 */
data class ItemDecision(
    val value: Any?,
    val type: ConfigType,
    val reason: ItemReason,
    val version: Int,
) {
    fun toWire(): Map<String, Any?> = mapOf(
        "value" to value,
        "type" to type.wireName,
        "reason" to reason.wireName,
        "version" to version,
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromWire(json: Map<String, Any?>?): ItemDecision? {
            if (json == null) return null
            val value = json["value"]
            val type = ConfigType.fromWire(json["type"] as? String)
            val reason = ItemReason.fromWire(json["reason"] as? String)
            val version = (json["version"] as? Number)?.toInt() ?: 0
            return ItemDecision(value, type, reason, version)
        }
    }
}

/**
 * Callback fired when an item's decision changes. `null` decision
 * means the item was removed remotely.
 */
fun interface ConfigChangeListener {
    fun onChange(decision: ItemDecision?)
}
