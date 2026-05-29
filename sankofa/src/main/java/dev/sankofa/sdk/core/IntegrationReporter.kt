package dev.sankofa.sdk.core

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Fire-and-forget POST of the integration audit result to
 * `POST /api/v1/handshake/integrations`. Mirrors the RN + Flutter + Web
 * reporters byte-for-byte on the wire — the server treats every payload
 * the same way.
 *
 * Errors are swallowed. The audit also stays in memory on the client
 * so the next launch re-runs and tries again.
 */
object IntegrationReporter {

    private const val SDK_NAME = "android"
    private const val SDK_VERSION = dev.sankofa.sdk.SankofaVersion.SDK_VERSION
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()

    /** Lightweight client used only for the reverse-handshake POST. The
     *  shared `SankofaHttpClient` doesn't expose its OkHttpClient and is
     *  scoped to analytics ingestion, so we build a tiny dedicated one
     *  here. Same approach the existing fetchHandshake() uses.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * @param baseEndpoint absolute base URL, e.g. https://api.sankofa.dev
     * @param appVersion host app's versionName (or "" if unknown)
     */
    fun report(
        baseEndpoint: String,
        apiKey: String,
        appVersion: String,
        statuses: List<ModuleIntegrationStatus>,
        debug: Boolean,
        onLog: (String) -> Unit,
    ) {
        if (apiKey.isBlank() || baseEndpoint.isBlank() || statuses.isEmpty()) return

        val payload = mapOf(
            "sdk" to SDK_NAME,
            "sdk_version" to SDK_VERSION,
            "platform" to "android",
            "app_version" to appVersion,
            "integrations" to statuses.map { s ->
                mapOf(
                    "module" to s.module,
                    "level" to s.level.wire,
                    "missing" to s.missing,
                    "warnings" to s.warnings,
                )
            },
        )

        val body = gson.toJson(payload).toRequestBody(JSON)
        val request = Request.Builder()
            .url(baseEndpoint.trimEnd('/') + "/api/v1/handshake/integrations")
            .addHeader("x-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) {
                    if (debug) onLog("[Sankofa] Integration report rejected (${res.code})")
                    return
                }
                if (debug) {
                    val bodyText = res.body?.string() ?: ""
                    onLog("[Sankofa] Integration report OK — $bodyText")
                }
            }
        } catch (t: Throwable) {
            if (debug) onLog("[Sankofa] Integration report failed: ${t.message}")
        }
    }
}
