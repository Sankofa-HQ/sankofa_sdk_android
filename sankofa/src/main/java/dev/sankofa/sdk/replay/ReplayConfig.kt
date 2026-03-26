package dev.sankofa.sdk.replay

/**
 * Server-driven configuration for session replay.
 * Fetched from [/api/replay/config] at the start of each new session.
 * Mirrors [sankofa_sdk_flutter/lib/src/replay/sankofa_replay_config.dart].
 */
internal data class ReplayConfig(
    val enabled: Boolean = true,
    val sampleRate: Double = 1.0,
    val highFidelityTriggers: List<String> = listOf("app_crash", "payment_failed"),
    val highFidelityDurationSeconds: Int = 30,
    val maskAllInputs: Boolean = true,
    val captureNetwork: Boolean = false,
) {
    companion object {
        fun defaults() = ReplayConfig()

        fun fromJson(json: Map<*, *>): ReplayConfig = ReplayConfig(
            enabled = json["enabled"] as? Boolean ?: true,
            sampleRate = (json["sample_rate"] as? Number)?.toDouble() ?: 1.0,
            highFidelityTriggers = (json["high_fidelity_triggers"] as? List<*>)
                ?.filterIsInstance<String>()
                ?: listOf("app_crash", "payment_failed"),
            highFidelityDurationSeconds = (json["high_fidelity_duration_seconds"] as? Number)?.toInt() ?: 30,
            maskAllInputs = json["mask_all_inputs"] as? Boolean ?: true,
            captureNetwork = json["capture_network"] as? Boolean ?: false,
        )
    }
}
