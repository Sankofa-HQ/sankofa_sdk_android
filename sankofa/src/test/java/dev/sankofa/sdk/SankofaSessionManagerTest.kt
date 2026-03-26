package dev.sankofa.sdk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.sankofa.sdk.core.SankofaSessionManager
import dev.sankofa.sdk.util.SankofaLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SankofaSessionManagerTest {

    private lateinit var context: Context
    private val logger = SankofaLogger(debug = false)
    private var newSessionCallCount = 0
    private var lastSessionId: String? = null

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sankofa_session", Context.MODE_PRIVATE)
            .edit().clear().commit()
        newSessionCallCount = 0
        lastSessionId = null
    }

    private fun buildManager() = SankofaSessionManager(
        context = context,
        logger = logger,
        onNewSession = { id ->
            newSessionCallCount++
            lastSessionId = id
        },
    )

    @Test
    fun `first refresh creates a session`() = runTest {
        val manager = buildManager()
        manager.refresh()
        assertNotNull(manager.sessionId)
        assert(manager.sessionId.isNotBlank())
        assert(newSessionCallCount == 1)
    }

    @Test
    fun `refresh within timeout keeps same session`() = runTest {
        val manager = buildManager()
        manager.refresh()
        val firstId = manager.sessionId
        manager.refresh() // immediately – should not expire
        assert(manager.sessionId == firstId)
        assert(newSessionCallCount == 1) // only one new session event
    }

    @Test
    fun `startNewSession generates a different session id`() = runTest {
        val manager = buildManager()
        manager.refresh()
        val firstId = manager.sessionId
        manager.startNewSession()
        assertNotEquals(firstId, manager.sessionId)
        assert(newSessionCallCount == 2)
    }
}
