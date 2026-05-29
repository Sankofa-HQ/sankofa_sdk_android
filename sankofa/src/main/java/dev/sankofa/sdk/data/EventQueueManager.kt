package dev.sankofa.sdk.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.sankofa.sdk.data.db.AppDatabase
import dev.sankofa.sdk.data.db.EventDao
import dev.sankofa.sdk.data.db.EventEntity
import dev.sankofa.sdk.network.BatchOutcome
import dev.sankofa.sdk.network.SankofaCommand
import dev.sankofa.sdk.network.SankofaHttpClient
import dev.sankofa.sdk.util.SankofaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * The offline-first event queue.
 *
 * Every call to [enqueue] writes immediately to the Room DB on [Dispatchers.IO].
 * The UI thread is never blocked. A flush is triggered automatically when the
 * queue reaches [batchSize] events, and can also be triggered externally (by the
 * 30-second timer or bg lifecycle event) via [flush].
 *
 * [dao] is injectable for unit testing (pass an in-memory Room DAO).
 * In production, leave [dao] null and it will be resolved from [AppDatabase.getInstance].
 */
internal class EventQueueManager(
    context: Context,
    private val httpClient: SankofaHttpClient,
    private val logger: SankofaLogger,
    private val batchSize: Int = 50,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    dao: EventDao? = null,
) {
    var onCommandsReceived: ((List<SankofaCommand>) -> Unit)? = null
    private val dao: EventDao = dao ?: AppDatabase.getInstance(context).eventDao()
    private val gson = Gson()
    private val flushMutex = Mutex()

    companion object {
        /**
         * Hard cap on queued rows. If the device is offline for a long time —
         * or the queue is wedged behind an unreachable server / rejected
         * API key — the oldest rows beyond this are evicted on enqueue so the
         * table can't grow without bound and fill the disk.
         */
        private const val MAX_QUEUED_EVENTS = 10_000

        /** Events older than this are dropped on flush; stale analytics aren't worth keeping. */
        private val MAX_EVENT_AGE_MS = TimeUnit.DAYS.toMillis(7)
    }

    /**
     * Enqueues a single event. Fire-and-forget from the caller's perspective.
     * The actual write happens on the provided [scope] (IO in production, test scope in tests).
     */
    fun enqueue(event: Map<String, Any>) {
        scope.launch {
            val payload = gson.toJson(event)
            this@EventQueueManager.dao.insertEvent(EventEntity(payload = payload))
            logger.debug("📥 Enqueued: ${event["event"] ?: event["type"]}")

            val count = this@EventQueueManager.dao.countEvents()
            // Bound the queue. Eviction is oldest-first so the freshest events
            // (most useful) survive an extended outage.
            if (count > MAX_QUEUED_EVENTS) {
                val overflow = count - MAX_QUEUED_EVENTS
                this@EventQueueManager.dao.deleteOldest(overflow)
                logger.debug("🧹 Queue over cap — evicted $overflow oldest event(s)")
            }
            if (count >= batchSize) {
                flush()
            }
        }
    }

    /**
     * Reads up to [batchSize] events, uploads them in one `/api/v1/batch`
     * request, and disposes of the rows according to the server's response:
     *  - delivered (2xx) or permanently rejected (non-auth 4xx) → delete;
     *  - transient failure (5xx / network / auth / throttle) → retain for retry.
     *
     * Protected by a [Mutex] so only one flush runs at a time even if called concurrently.
     */
    suspend fun flush() {
        flushMutex.withLock {
            // Drop stale events first so they can neither wedge nor bloat the queue.
            dao.deleteOlderThan(System.currentTimeMillis() - MAX_EVENT_AGE_MS)

            val rows = dao.getOldestEvents(batchSize)
            if (rows.isEmpty()) return

            // Parse each stored payload back into a JsonObject, preserving the
            // original number tokens (JsonParser keeps ints as ints — unlike a
            // Map round-trip, which coerces every number to a Double and would
            // turn `120` into `120.0` on the wire). A row that can't be parsed
            // is corrupt and is dropped immediately rather than retried forever.
            val sendable = ArrayList<JsonObject>(rows.size)
            val sendableIds = ArrayList<Long>(rows.size)
            val corruptIds = ArrayList<Long>()
            for (row in rows) {
                val obj = runCatching { JsonParser.parseString(row.payload).asJsonObject }.getOrNull()
                if (obj == null) corruptIds.add(row.id) else {
                    sendable.add(obj)
                    sendableIds.add(row.id)
                }
            }
            if (corruptIds.isNotEmpty()) {
                logger.debug("🗑 Dropping ${corruptIds.size} corrupt event(s)")
                dao.deleteEvents(corruptIds)
            }
            if (sendable.isEmpty()) return

            logger.debug("🚀 Flushing ${sendable.size} events…")
            val result = httpClient.sendBatch(sendable)

            when (result.outcome) {
                BatchOutcome.DELIVERED -> {
                    dao.deleteEvents(sendableIds)
                    logger.debug("✅ Flushed ${sendable.size} events")
                    result.commands?.let { onCommandsReceived?.invoke(it) }
                }
                BatchOutcome.DROP -> {
                    // Permanently rejected — remove so it can't wedge the queue.
                    dao.deleteEvents(sendableIds)
                }
                BatchOutcome.RETAIN -> {
                    logger.debug("⚠️ Flush failed – ${sendable.size} events retained for next attempt")
                }
            }
        }
    }
}
