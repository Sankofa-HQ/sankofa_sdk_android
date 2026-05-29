package dev.sankofa.sdk.replay

import android.content.Context
import android.content.SharedPreferences
import dev.sankofa.sdk.network.SankofaHttpClient
import dev.sankofa.sdk.util.SankofaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A captured frame with its metadata.
 */
internal data class FrameData(
    val bytes: ByteArray,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val scrollOffsetY: Int,
    val screen: String
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

    // All access to [frameBuffer] is guarded by [frameLock]; it is touched by
    // both the channel-drain coroutine (add) and flush()/uploadBatch (snapshot
    // + remove), which can run on different threads. [uploadMutex] serializes
    // uploads so two concurrent uploadBatch calls can't reuse the same
    // chunkIndex (server-side overwrite) or interleave buffer removal.
    private val frameBuffer = ArrayList<FrameData>()
    private val frameLock = Any()
    private val uploadMutex = Mutex()
    private val touchEventsBuffer = CopyOnWriteArrayList<Map<String, Any>>()

    private var sessionId: String = ""
    private var distinctId: String = "anonymous"
    private var chunkIndex: Int = 0

    /**
     * Read-only accessor for the active replay session id. Returns
     * the empty string when configure() hasn't been called yet
     * (replay sampled out, recordSessions=false, or pre-handshake);
     * sibling modules treat the empty case as "no recording".
     */
    internal fun activeSessionId(): String = sessionId

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Drain the channel and batch-upload frames on a background coroutine
        scope.launch {
            for (frame in frameChannel) {
                val shouldUpload = synchronized(frameLock) {
                    frameBuffer.add(frame)
                    // Bound memory: if uploads have been failing (server down,
                    // offline), drop the oldest frames so base64'd WebP bytes
                    // can't accumulate without limit and OOM the process.
                    while (frameBuffer.size > MAX_BUFFERED_FRAMES) {
                        frameBuffer.removeAt(0)
                    }
                    frameBuffer.size >= chunkFrameCount
                }
                if (shouldUpload) uploadBatch()
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
    fun enqueueFrame(compressedFrame: ByteArray, captureTimestamp: Long, width: Int, height: Int, scrollOffsetY: Int, screen: String) {
        if (sessionId.isEmpty()) return
        frameChannel.trySend(FrameData(compressedFrame, captureTimestamp, width, height, scrollOffsetY, screen))
    }

    /** Called from [ReplayRecorder] when a user touches the screen. */
    fun enqueueTouchEvent(
        x: Int,
        y: Int,
        absoluteY: Int,
        scrollOffsetY: Int,
        screen: String,
        timestamp: Long,
        type: Int
    ) {
        if (sessionId.isEmpty()) return
        // Formatted to loosely mirror the rrweb type: 3 MouseInteraction payload.
        //
        // `screen` is intentionally a TOP-LEVEL field on the event (not on
        // `data`).  The replay worker reads `event.screen` for high-precision
        // attribution when a chunk spans a screen change — putting it on
        // `data` makes the worker fall back to `frames[0].screen` and
        // attribute every later tap to the first frame's screen.  iOS/Web
        // both place `screen` at the event level; we mirror that.
        //
        // `scroll_y` was previously emitted on `data` but the worker has its
        // own scroll-aware normalization (absoluteY / screenH) and never
        // reads `data.scroll_y`.  Dropped from the wire shape to keep the
        // payload tight.
        touchEventsBuffer.add(
            mapOf(
                "type" to 3,
                "data" to mapOf(
                    "source" to 2, // MouseInteraction
                    "type" to type, // 1 = MouseDown, 0 = MouseUp, etc.
                    "id" to 1,
                    "x" to x,
                    "y" to absoluteY
                ),
                "timestamp" to timestamp,
                "screen" to screen
            )
        )
        // Bound the touch buffer the same way as frames — a long upload outage
        // shouldn't let tap events grow without limit.
        while (touchEventsBuffer.size > MAX_BUFFERED_TOUCH_EVENTS) {
            touchEventsBuffer.removeAt(0)
        }
    }

    /** Force-flush remaining frames – called when the app goes to background. */
    suspend fun flush() {
        val hasFrames = synchronized(frameLock) { frameBuffer.isNotEmpty() }
        if (hasFrames) uploadBatch()
    }

    private suspend fun uploadBatch() = uploadMutex.withLock {
        val framesAttemptingUpload = synchronized(frameLock) {
            if (frameBuffer.isEmpty() || sessionId.isEmpty()) return@withLock
            frameBuffer.toList()
        }
        val eventsAttemptingUpload = touchEventsBuffer.toList()

        val frames = framesAttemptingUpload.map { 
            mapOf(
                "timestamp" to it.timestampMs,
                "image_base64" to Base64.encodeToString(it.bytes, Base64.NO_WRAP),
                "scroll_y" to it.scrollOffsetY,
                "screen" to it.screen
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
                "pixel_ratio" to 1.0,
                "\$os" to "Android"
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

            // Only clear if the upload succeeded. Remove exactly the frames we
            // sent (newer frames added during the upload stay queued).
            synchronized(frameLock) { frameBuffer.removeAll(framesAttemptingUpload) }
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

        /**
         * Hard caps so a sustained upload outage can't grow memory without
         * bound. At ~2fps, 300 frames is ~2.5 min of unsent recording; beyond
         * that the oldest frames are dropped (the recent tail is more useful).
         */
        private const val MAX_BUFFERED_FRAMES = 300
        private const val MAX_BUFFERED_TOUCH_EVENTS = 2_000
    }
}
