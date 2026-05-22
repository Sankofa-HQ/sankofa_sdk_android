package dev.sankofa.sdk.core

/**
 * # Module integration self-audit (Android SDK)
 *
 * Mirrors the `ModuleIntegrationStatus` type used by the Flutter + RN
 * + Web SDKs. Reported to the server via
 * `POST /api/v1/handshake/integrations` so the dashboard's SDK Health
 * page can flag silently-broken host integrations.
 *
 * Wire shape kept in lockstep with:
 *   - sdks/sankofa_sdk_react_native/src/core/integration.ts
 *   - sdks/sankofa_sdk_flutter/lib/src/core/module_registry.dart
 *   - sdks/sankofa_sdk_web/packages/browser/src/integration.ts
 *   - server/engine/ee/deploy/integration_health.go
 */

enum class ModuleIntegrationLevel(val wire: String) {
    FULL("full"),
    PARTIAL("partial"),
    BROKEN("broken"),
}

data class ModuleIntegrationStatus(
    val module: String,
    val level: ModuleIntegrationLevel,
    val missing: List<String>,
    val warnings: List<String>,
) {
    companion object {
        /** Derive the level from missing-count, matching RN + Flutter rule. */
        fun deriveLevel(missing: List<String>): ModuleIntegrationLevel = when {
            missing.isEmpty() -> ModuleIntegrationLevel.FULL
            missing.size >= 2 -> ModuleIntegrationLevel.BROKEN
            else -> ModuleIntegrationLevel.PARTIAL
        }
    }
}
