package dev.sankofa.sdk.data

import android.content.Context
import com.google.gson.Gson
import dev.sankofa.sdk.data.db.AppDatabase
import dev.sankofa.sdk.data.db.EventDao
import dev.sankofa.sdk.data.db.EventEntity
import dev.sankofa.sdk.network.SankofaHttpClient
import dev.sankofa.sdk.util.SankofaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val dao: EventDao = dao ?: AppDatabase.getInstance(context).eventDao()
    private val gson = Gson()
    private val flushMutex = Mutex()

    /**
     * Enqueues a single event. Fire-and-forget from the caller's perspective.
     * The actual write happens on the provided [scope] (IO in production, test scope in tests).
     */
    fun enqueue(event: Map<String, Any>) {
        scope.launch {
            val payload = gson.toJson(event)
            this@EventQueueManager.dao.insertEvent(EventEntity(payload = payload))
            logger.debug("📥 Enqueued: ${event["event"] ?: event["type"]}")

            if (this@EventQueueManager.dao.countEvents() >= batchSize) {
                flush()
            }
        }
    }

    /**
     * Reads up to [batchSize] events, uploads them, and deletes the successes.
     * Protected by a [Mutex] so only one flush runs at a time even if called concurrently.
     */
    suspend fun flush() {
        flushMutex.withLock {
            val events = dao.getOldestEvents(batchSize)
            if (events.isEmpty()) return

            logger.debug("🚀 Flushing ${events.size} events…")

            val payloads = events.map { gson.fromJson(it.payload, Map::class.java) }

            @Suppress("UNCHECKED_CAST")
            val success = httpClient.sendBatch(payloads as List<Map<String, Any>>)

            if (success) {
                dao.deleteEvents(events.map { it.id })
                logger.debug("✅ Flushed ${events.size} events")
            } else {
                logger.debug("⚠️ Flush failed – events retained for next attempt")
            }
        }
    }
}
