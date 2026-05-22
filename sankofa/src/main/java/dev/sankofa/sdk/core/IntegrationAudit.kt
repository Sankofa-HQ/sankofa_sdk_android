package dev.sankofa.sdk.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Audits the host Android app's Sankofa SDK integration. Runs once
 * after the first successful handshake; result is reported to the
 * server via [IntegrationReporter].
 *
 * Each check probes something cheap (manifest permission, package
 * info) — no network calls live in this function.
 */
object IntegrationAudit {

    fun audit(
        context: Context,
        handshakeOk: Boolean,
        appVersionFromHost: Boolean,
    ): ModuleIntegrationStatus {
        val missing = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Hard breakage: server unreachable / API key invalid. Most
        // common cause: wrong endpoint URL or revoked key.
        if (!handshakeOk) {
            missing.add(
                "Handshake to /api/v1/handshake did not succeed. Check the endpoint URL and that the API key is valid.",
            )
        }

        // INTERNET permission. Without it Sankofa cannot reach the
        // backend. Foreground apps generally have it auto-granted by
        // the manifest declaration, but some leaner manifests omit it.
        val hasInternet = context.checkSelfPermission(Manifest.permission.INTERNET) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasInternet) {
            missing.add(
                "AndroidManifest is missing <uses-permission android:name=\"android.permission.INTERNET\" />. " +
                    "Add it under the root <manifest> tag.",
            )
        }

        // Network state — soft signal used by retry/backoff. Not
        // required to ship events but very useful for diagnostics.
        val hasNetworkState = context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasNetworkState) {
            warnings.add(
                "AndroidManifest is missing ACCESS_NETWORK_STATE — connectivity-aware retry/backoff is degraded.",
            )
        }

        // App version warning. Without an explicit `appVersion` set via
        // `SankofaConfig`, the SDK falls back to `versionName` from
        // PackageInfo. That's usually right but may not match what the
        // host wants for cohort targeting (e.g. flavor suffixes).
        if (!appVersionFromHost) {
            warnings.add(
                "SankofaConfig.appVersion not set — falling back to PackageManager versionName. " +
                    "Set it explicitly if your release versioning differs from the package's.",
            )
        }

        // Debug-build warning. Production analytics in a debug build is
        // a common foot-gun: events flow but they pollute live data.
        if (0 != (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)) {
            warnings.add(
                "App is built in debug mode (FLAG_DEBUGGABLE). Events from this install will land in your live project unless filtered downstream.",
            )
        }

        // Min SDK version surface — informational only, but lets us
        // detect cases where the host targets an Android level that
        // pre-dates a feature we depend on. Today everything we use
        // works on API 21+; future modules may raise the bar.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            warnings.add(
                "Running on Android API ${Build.VERSION.SDK_INT} (< 21). Some Sankofa features may degrade.",
            )
        }

        return ModuleIntegrationStatus(
            module = "analytics",
            level = ModuleIntegrationStatus.deriveLevel(missing),
            missing = missing,
            warnings = warnings,
        )
    }
}
