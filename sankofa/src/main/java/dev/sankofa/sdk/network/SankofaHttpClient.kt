package dev.sankofa.sdk.network

import android.util.Log
import com.google.gson.Gson
import dev.sankofa.sdk.util.SankofaLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * A thin OkHttp wrapper that handles batch serialization, GZIP compression,
 * and the actual HTTP POST to the Sankofa ingestion endpoint.
 */
internal class SankofaHttpClient(
    private val apiKey: String,
    private val trackEndpoint: String,   // e.g. "https://api.sankofa.dev/api/v1/track"
    private val aliasEndpoint: String,   // e.g. "https://api.sankofa.dev/api/v1/alias"
    private val peopleEndpoint: String,  // e.g. "https://api.sankofa.dev/api/v1/people"
    private val logger: SankofaLogger,
) {
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Sends a batch of events (can be mixed types – track, alias, people).
     * The entire batch is serialized as a JSON array, then GZIP-compressed.
     * Returns true on HTTP 200, false on any failure (caller retains events).
     */
    fun sendBatch(events: List<Map<String, Any>>): Boolean {
        // Split by type: track events go to /track, others routed individually
        val trackEvents = events.filter { it["type"] != "alias" && it["type"] != "people" }
        val aliasEvents = events.filter { it["type"] == "alias" }
        val peopleEvents = events.filter { it["type"] == "people" }

        var allSuccess = true

        trackEvents.forEach { allSuccess = allSuccess && post(trackEndpoint, it) }
        aliasEvents.forEach { allSuccess = allSuccess && post(aliasEndpoint, it) }
        peopleEvents.forEach { allSuccess = allSuccess && post(peopleEndpoint, it) }

        return allSuccess
    }

    /**
     * Low-level POST: JSON → GZIP → OkHttp.
     */
    fun post(url: String, payload: Any): Boolean {
        return try {
            val json = gson.toJson(payload)
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("x-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            response.close()

            if (!success) logger.debug("❌ HTTP ${response.code} for $url")
            success
        } catch (e: Exception) {
            logger.debug("❌ Network error: ${e.message}")
            false
        }
    }
}
