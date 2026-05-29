package dev.sankofa.sdk

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sankofa.sdk.data.EventQueueManager
import dev.sankofa.sdk.data.db.AppDatabase
import dev.sankofa.sdk.data.db.EventEntity
import dev.sankofa.sdk.network.BatchOutcome
import dev.sankofa.sdk.network.SankofaBatchResult
import dev.sankofa.sdk.network.SankofaHttpClient
import dev.sankofa.sdk.util.SankofaLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EventQueueManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val logger = SankofaLogger(debug = false)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun makeManager(
        httpClient: SankofaHttpClient,
        batchSize: Int = 50,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = EventQueueManager(
        context = context,
        httpClient = httpClient,
        logger = logger,
        batchSize = batchSize,
        scope = scope,
        dao = db.eventDao(),
    )

    @Test
    fun `enqueue and flush sends events to http client`() = runTest {
        val httpClient = mockk<SankofaHttpClient>()
        coEvery { httpClient.sendBatch(any()) } returns SankofaBatchResult(BatchOutcome.DELIVERED)
        val manager = makeManager(httpClient, scope = this)

        manager.enqueue(mapOf("event" to "test_event"))
        advanceUntilIdle()
        manager.flush()
        advanceUntilIdle()

        coVerify(atLeast = 1) { httpClient.sendBatch(any()) }
    }

    @Test
    fun `flush does nothing when queue is empty`() = runTest {
        val httpClient = mockk<SankofaHttpClient>()
        coEvery { httpClient.sendBatch(any()) } returns SankofaBatchResult(BatchOutcome.DELIVERED)
        val manager = makeManager(httpClient, scope = this)

        manager.flush()
        advanceUntilIdle()

        coVerify(exactly = 0) { httpClient.sendBatch(any()) }
    }

    @Test
    fun `flush is attempted even on network failure`() = runTest {
        val httpClient = mockk<SankofaHttpClient>()
        coEvery { httpClient.sendBatch(any()) } returns SankofaBatchResult(BatchOutcome.RETAIN)
        val manager = makeManager(httpClient, scope = this)

        manager.enqueue(mapOf("event" to "test_event"))
        advanceUntilIdle()
        manager.flush()
        advanceUntilIdle()

        coVerify(atLeast = 1) { httpClient.sendBatch(any()) }
    }

    @Test
    fun `flush batches multiple pre-seeded events in one sendBatch call`() = runTest {
        val httpClient = mockk<SankofaHttpClient>()
        coEvery { httpClient.sendBatch(any()) } returns SankofaBatchResult(BatchOutcome.DELIVERED)
        val manager = makeManager(httpClient, batchSize = 50, scope = this)

        // Seed the DAO directly so we know exactly what's in the queue before flush
        val dao = db.eventDao()
        dao.insertEvent(EventEntity(payload = """{"event":"e1"}"""))
        dao.insertEvent(EventEntity(payload = """{"event":"e2"}"""))
        dao.insertEvent(EventEntity(payload = """{"event":"e3"}"""))

        manager.flush()
        advanceUntilIdle()

        coVerify(atLeast = 1) { httpClient.sendBatch(match { it.size == 3 }) }
    }

    @Test
    fun `delivered batch deletes the rows`() = runTest {
        val httpClient = mockk<SankofaHttpClient>()
        coEvery { httpClient.sendBatch(any()) } returns SankofaBatchResult(BatchOutcome.DELIVERED)
        val manager = makeManager(httpClient, scope = this)
        val dao = db.eventDao()
        dao.insertEvent(EventEntity(payload = """{"event":"e1"}"""))
        dao.insertEvent(EventEntity(payload = """{"event":"e2"}"""))

        manager.flush()
        advanceUntilIdle()

        assert(dao.countEvents() == 0) { "delivered events should be deleted" }
    }

    @Test
    fun `retained batch keeps the rows for retry`() = runTest {
        val httpClient = mockk<SankofaHttpClient>()
        coEvery { httpClient.sendBatch(any()) } returns SankofaBatchResult(BatchOutcome.RETAIN)
        val manager = makeManager(httpClient, scope = this)
        val dao = db.eventDao()
        dao.insertEvent(EventEntity(payload = """{"event":"e1"}"""))
        dao.insertEvent(EventEntity(payload = """{"event":"e2"}"""))

        manager.flush()
        advanceUntilIdle()

        // A transient failure must NOT lose data — both events stay queued.
        assert(dao.countEvents() == 2) { "retained events should remain queued" }
    }

    @Test
    fun `permanently rejected batch is dropped to avoid a poison pill`() = runTest {
        val httpClient = mockk<SankofaHttpClient>()
        coEvery { httpClient.sendBatch(any()) } returns SankofaBatchResult(BatchOutcome.DROP)
        val manager = makeManager(httpClient, scope = this)
        val dao = db.eventDao()
        dao.insertEvent(EventEntity(payload = """{"event":"e1"}"""))

        manager.flush()
        advanceUntilIdle()

        // A 4xx-rejected batch would never succeed; dropping it prevents it
        // from wedging the whole queue forever.
        assert(dao.countEvents() == 0) { "permanently rejected events should be dropped" }
    }

    @Test
    fun `corrupt payloads are dropped and not sent`() = runTest {
        val httpClient = mockk<SankofaHttpClient>()
        coEvery { httpClient.sendBatch(any()) } returns SankofaBatchResult(BatchOutcome.DELIVERED)
        val manager = makeManager(httpClient, scope = this)
        val dao = db.eventDao()
        dao.insertEvent(EventEntity(payload = "this-is-not-json"))
        dao.insertEvent(EventEntity(payload = """{"event":"good"}"""))

        manager.flush()
        advanceUntilIdle()

        // Only the well-formed event is sent; the corrupt one is purged.
        coVerify(atLeast = 1) { httpClient.sendBatch(match { it.size == 1 }) }
        assert(dao.countEvents() == 0) { "both the corrupt and delivered rows should be gone" }
    }

    @Test
    fun `integer properties are preserved (not coerced to doubles)`() = runTest {
        val httpClient = mockk<SankofaHttpClient>()
        coEvery { httpClient.sendBatch(any()) } returns SankofaBatchResult(BatchOutcome.DELIVERED)
        val manager = makeManager(httpClient, scope = this)
        val dao = db.eventDao()
        dao.insertEvent(EventEntity(payload = """{"event":"buy","properties":{"price":120}}"""))

        manager.flush()
        advanceUntilIdle()

        // The forwarded payload must keep `120` as an integer token, not `120.0`.
        coVerify(atLeast = 1) {
            httpClient.sendBatch(match { batch ->
                val props = batch.first().getAsJsonObject("properties")
                props.get("price").asString == "120"
            })
        }
    }
}
