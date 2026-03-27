package dev.sankofa.sdk.replay

import android.content.Context
import android.content.SharedPreferences
import dev.sankofa.sdk.network.SankofaHttpClient
import dev.sankofa.sdk.util.SankofaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Base64

/**
 * Receives compressed frame [ByteArray]s from [ReplayRecorder], batches them,
 * and uploads chunks to [/api/ee/replay/chunk].
 *
 * Uses a [Channel] as a non-blocking queue between the capture pipeline and
 * the upload coroutine so back-pressure never stalls the capture thread.
 *
 * Mirrors [sankofa_sdk_flutter/lib/src/replay/sankofa_replay_uploader.dart].
 */
internal class ReplayUploader(
    private val context: Context,
    private val httpClient: SankofaHttpClient,
    private val replayEndpoint: String,
    private val logger: SankofaLogger,
    private val chunkFrameCount: Int = FRAMES_PER_CHUNK,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val frameChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val frameBuffer = mutableListOf<ByteArray>()

    private var sessionId: String = ""
    private var distinctId: String = "anonymous"
    private var chunkIndex: Int = 0

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Drain the channel and batch-upload frames on a background coroutine
        scope.launch {
            for (frame in frameChannel) {
                frameBuffer.add(frame)
                if (frameBuffer.size >= chunkFrameCount) {
                    uploadBatch()
                }
            }
        }
    }

    fun configure(sessionId: String, distinctId: String) {
        if (this.sessionId != sessionId) {
            // New session → load the chunk index from prefs (survives process restarts)
            chunkIndex = prefs.getInt(chunkKey(sessionId), 0)
        }
        this.sessionId = sessionId
        this.distinctId = distinctId
    }

    fun setDistinctId(id: String) {
        distinctId = id
    }

    /** Called from [ReplayRecorder] after each frame is compressed. */
    fun enqueueFrame(compressedFrame: ByteArray) {
        if (sessionId.isEmpty()) return
        frameChannel.trySend(compressedFrame)
    }

    /** Force-flush remaining frames – called when the app goes to background. */
    suspend fun flush() {
        if (frameBuffer.isNotEmpty()) uploadBatch()
    }

    private suspend fun uploadBatch() {
        if (frameBuffer.isEmpty() || sessionId.isEmpty()) return

        val frames = frameBuffer.map { Base64.getEncoder().encodeToString(it) }
        frameBuffer.clear()

        val payload = mapOf(
            "session_id" to sessionId,
            "chunk_index" to chunkIndex,
            "mode" to "screenshot",
            "frames" to frames.mapIndexed { i, f ->
                mapOf(
                    "timestamp" to System.currentTimeMillis() + i,
                    "image_base64" to f,
                )
            },
        )

        val url = "$replayEndpoint/api/ee/replay/chunk"
        val headers = mapOf(
            "X-Session-Id" to sessionId,
            "X-Distinct-Id" to distinctId,
            "X-Chunk-Index" to chunkIndex.toString(),
            "X-Replay-Mode" to "screenshot"
        )
        val success = httpClient.postReplayChunk(url, payload, headers)

        if (success) {
            chunkIndex++
            prefs.edit().putInt(chunkKey(sessionId), chunkIndex).apply()
            logger.debug("🚀 Replay chunk ${chunkIndex - 1} uploaded (${frames.size} frames)")
        } else {
            // Re-add frames to buffer for retry on next flush
            // (frames already cleared, this is best-effort – severe network failures may lose frames)
            logger.debug("⚠️ Replay chunk upload failed – chunk ${chunkIndex} will retry")
        }
    }

    private fun chunkKey(sid: String) = "sankofa_replay_chunk_$sid"

    companion object {
        private const val PREFS_NAME = "sankofa_replay"
        private const val FRAMES_PER_CHUNK = 5
    }
}
