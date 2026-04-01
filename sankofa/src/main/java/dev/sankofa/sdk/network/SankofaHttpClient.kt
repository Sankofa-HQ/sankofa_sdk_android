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
import java.util.zip.GZIPOutputStream

internal data class SankofaCommand(
    val type: String,
    val params: Map<String, Any>?
)

internal data class SankofaResponse(
    val success: Boolean,
    val commands: List<SankofaCommand>? = null
)

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
    fun sendBatch(events: List<Map<String, Any>>): SankofaResponse {
        val trackEvents = events.filter { it["type"] != "alias" && it["type"] != "people" }
        val aliasEvents = events.filter { it["type"] == "alias" }
        val peopleEvents = events.filter { it["type"] == "people" }

        var allSuccess = true
        val allCommands = mutableListOf<SankofaCommand>()

        trackEvents.forEach {
            val res = post(trackEndpoint, it)
            allSuccess = allSuccess && res.success
            res.commands?.let { allCommands.addAll(it) }
        }
        aliasEvents.forEach { allSuccess = allSuccess && post(aliasEndpoint, it).success }
        peopleEvents.forEach { allSuccess = allSuccess && post(peopleEndpoint, it).success }

        return SankofaResponse(allSuccess, allCommands.distinctBy { it.type })
    }

    /**
     * Low-level POST: JSON → GZIP → OkHttp.
     */
    fun post(url: String, payload: Any): SankofaResponse {
        return try {
            val json = gson.toJson(payload)
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("x-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                val responseBody = response.body?.string()
                
                var commands: List<SankofaCommand>? = null
                if (success && !responseBody.isNullOrBlank()) {
                    try {
                        val parsed = gson.fromJson(responseBody, Map::class.java)
                        @Suppress("UNCHECKED_CAST")
                        val cmdsList = parsed["commands"] as? List<Map<String, Any>>
                        commands = cmdsList?.map {
                            SankofaCommand(it["type"] as String, it["params"] as? Map<String, Any>)
                        }
                    } catch (e: Exception) {
                        // Not JSON or missing commands
                    }
                }

                if (!success) logger.debug("❌ HTTP ${response.code} for $url")
                SankofaResponse(success, commands)
            }
        } catch (e: Exception) {
            logger.debug("❌ Network error: ${e.message}")
            SankofaResponse(false)
        }
    }

    /**
     * Dedicated method for uploading replay chunks with required headers and GZIP compression.
     */
    fun postReplayChunk(url: String, payload: Any, headers: Map<String, String>): Boolean {
        return try {
            val json = gson.toJson(payload)
            val compressed = gzip(json)
            val body = compressed.toRequestBody("application/json".toMediaType())
            
            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("x-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Content-Encoding", "gzip")
                .post(body)
                
            headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }

            val request = requestBuilder.build()
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

    private fun gzip(data: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzip -> gzip.write(data.toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }
}
