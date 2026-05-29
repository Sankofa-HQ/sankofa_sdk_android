package dev.sankofa.sdk.network

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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

/**
 * How the queue should treat a batch after an upload attempt.
 *
 * The old client ANDed per-event POST results into a single boolean, which
 * meant one rejected event forced the whole batch to be re-sent forever (and
 * a permanently-invalid event wedged the queue). We now classify the single
 * batch response so the queue can delete delivered/permanently-rejected rows
 * and retain only genuinely retriable ones.
 */
internal enum class BatchOutcome {
    /** 2xx / 202 — server accepted (or intentionally discarded) the batch. Delete the rows. */
    DELIVERED,

    /** Permanent client error (malformed/oversized batch). Delete the rows to avoid a poison pill. */
    DROP,

    /** Transient failure (5xx, network, auth, rate-limit). Keep the rows for a later retry. */
    RETAIN,
}

internal data class SankofaBatchResult(
    val outcome: BatchOutcome,
    val commands: List<SankofaCommand>? = null,
)

/**
 * A thin OkHttp wrapper around the unified ingestion endpoint.
 *
 * Events are uploaded as a single request to `POST /api/v1/batch` with the
 * `{ "operations": [ { "type", "payload" } ] }` envelope — the same wire
 * contract the Flutter, iOS, and Web SDKs use. The server does NOT gzip-decode
 * the ingestion endpoints, so analytics bodies are sent as plain JSON; only
 * replay chunks (a different endpoint) are GZIP-compressed.
 */
internal class SankofaHttpClient(
    private val apiKey: String,
    private val batchEndpoint: String,   // e.g. "https://api.sankofa.dev/api/v1/batch"
    private val logger: SankofaLogger,
) {
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads [events] to `/api/v1/batch` in a single request.
     *
     * Each event is wrapped as `{ "type", "payload" }`; the raw [JsonObject]
     * payload is forwarded verbatim so integer fidelity is preserved (no
     * Map round-trip that would coerce `120` into `120.0`). The `type` field
     * inside the event selects the operation type and is harmlessly carried
     * along in the payload (the server ignores unknown fields).
     */
    fun sendBatch(events: List<JsonObject>): SankofaBatchResult {
        if (events.isEmpty()) return SankofaBatchResult(BatchOutcome.DELIVERED)

        val operations = JsonArray(events.size)
        for (event in events) {
            val typeField = event.get("type")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
            val opType = when (typeField) {
                "alias" -> "alias"
                "people" -> "people"
                else -> "track"
            }
            operations.add(JsonObject().apply {
                addProperty("type", opType)
                add("payload", event)
            })
        }
        val root = JsonObject().apply { add("operations", operations) }
        val json = gson.toJson(root)

        return try {
            val request = Request.Builder()
                .url(batchEndpoint)
                .addHeader("x-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                when {
                    // 2xx success, or 202 "discarded" (server intentionally
                    // dropped unidentifiable events — retrying would just
                    // resend garbage).
                    response.isSuccessful -> {
                        val commands = parseCommands(response.body?.string())
                        SankofaBatchResult(BatchOutcome.DELIVERED, commands)
                    }
                    // 408 Request Timeout / 429 Too Many Requests are transient.
                    code == 408 || code == 429 -> {
                        logger.debug("⏳ Batch throttled (HTTP $code) — retaining ${events.size} events")
                        SankofaBatchResult(BatchOutcome.RETAIN)
                    }
                    // 401/403 — bad/rotated API key or unauthorized origin. A
                    // developer may fix the key, so retain (bounded by the
                    // queue cap) rather than silently destroy the user's data.
                    code == 401 || code == 403 -> {
                        logger.warn("🔒 Batch rejected (HTTP $code) — check API key / origin; retaining")
                        SankofaBatchResult(BatchOutcome.RETAIN)
                    }
                    // Other 4xx — malformed/oversized batch. Resending the
                    // same bytes will never succeed, so drop to avoid wedging
                    // the whole queue behind one poison-pill event.
                    code in 400..499 -> {
                        logger.warn("🗑 Batch rejected (HTTP $code) — dropping ${events.size} events")
                        SankofaBatchResult(BatchOutcome.DROP)
                    }
                    // 5xx — server-side, retry later.
                    else -> {
                        logger.debug("❌ Batch failed (HTTP $code) — retaining ${events.size} events")
                        SankofaBatchResult(BatchOutcome.RETAIN)
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug("❌ Network error: ${e.message}")
            SankofaBatchResult(BatchOutcome.RETAIN)
        }
    }

    /** Extracts the optional server-command list from a batch response body. */
    private fun parseCommands(body: String?): List<SankofaCommand>? {
        if (body.isNullOrBlank()) return null
        return try {
            val parsed = gson.fromJson(body, Map::class.java) ?: return null
            @Suppress("UNCHECKED_CAST")
            val cmdsList = parsed["commands"] as? List<Map<String, Any>> ?: return null
            cmdsList.mapNotNull { cmd ->
                val type = cmd["type"] as? String ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                SankofaCommand(type, cmd["params"] as? Map<String, Any>)
            }.ifEmpty { null }
        } catch (e: Exception) {
            // Not JSON or no commands — nothing to act on.
            null
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

            client.newCall(requestBuilder.build()).execute().use { response ->
                val success = response.isSuccessful
                if (!success) logger.debug("❌ HTTP ${response.code} for $url")
                success
            }
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
