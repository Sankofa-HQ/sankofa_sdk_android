package dev.sankofa.sdk.replay

import android.content.Context
import android.content.SharedPreferences
import dev.sankofa.sdk.network.SankofaHttpClient
import dev.sankofa.sdk.util.SankofaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import android.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A captured frame with its metadata.
 */
internal data class FrameData(
    val bytes: ByteArray,
    val timestampMs: Long,
    val width: Int,
    val height: Int
)

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
    private val frameChannel = Channel<FrameData>(capacity = Channel.UNLIMITED)
    private val frameBuffer = mutableListOf<FrameData>()
    private val touchEventsBuffer = CopyOnWriteArrayList<Map<String, Any>>()

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
    fun enqueueFrame(compressedFrame: ByteArray, captureTimestamp: Long, width: Int, height: Int) {
        if (sessionId.isEmpty()) return
        frameChannel.trySend(FrameData(compressedFrame, captureTimestamp, width, height))
    }

    /** Called from [ReplayRecorder] when a user touches the screen. */
    fun enqueueTouchEvent(x: Int, y: Int, timestamp: Long, type: Int) {
        if (sessionId.isEmpty()) return
        // Formatted to loosely mirror the rrweb type: 3 MouseInteraction payload
        touchEventsBuffer.add(
            mapOf(
                "type" to 3,
                "data" to mapOf(
                    "source" to 2, // MouseInteraction
                    "type" to type, // 1 = MouseDown, 0 = MouseUp
                    "id" to 1,
                    "x" to x,
                    "y" to y
                ),
                "timestamp" to timestamp
            )
        )
    }

    /** Force-flush remaining frames – called when the app goes to background. */
    suspend fun flush() {
        if (frameBuffer.isNotEmpty()) uploadBatch()
    }

    private suspend fun uploadBatch() {
        if (frameBuffer.isEmpty() || sessionId.isEmpty()) return

        val framesAttemptingUpload = frameBuffer.toList()
        val eventsAttemptingUpload = touchEventsBuffer.toList()

        val frames = framesAttemptingUpload.map { 
            mapOf(
                "timestamp" to it.timestampMs,
                "image_base64" to Base64.encodeToString(it.bytes, Base64.NO_WRAP)
            )
        }

        val lastFrame = framesAttemptingUpload.last()

        val payload = mutableMapOf<String, Any>(
            "session_id" to sessionId,
            "chunk_index" to chunkIndex,
            "mode" to "screenshot",
            "device_context" to mapOf(
                "screen_width" to lastFrame.width,
                "screen_height" to lastFrame.height,
                "pixel_ratio" to 1.0
            ),
            "frames" to frames
        )

        if (eventsAttemptingUpload.isNotEmpty()) {
            payload["events"] = eventsAttemptingUpload
        }

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
            
            // Only clear if the upload succeeded
            frameBuffer.removeAll(framesAttemptingUpload)
            touchEventsBuffer.removeAll(eventsAttemptingUpload)
            
            logger.debug("🚀 Replay chunk ${chunkIndex - 1} uploaded (${frames.size} frames)")
        } else {
            logger.debug("⚠️ Replay chunk upload failed – keeping in buffer for retry")
        }
    }

    private fun chunkKey(sid: String) = "sankofa_replay_chunk_$sid"

    companion object {
        private const val PREFS_NAME = "sankofa_replay"
        private const val FRAMES_PER_CHUNK = 5
    }
}
