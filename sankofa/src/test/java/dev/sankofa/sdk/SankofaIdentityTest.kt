package dev.sankofa.sdk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.sankofa.sdk.core.SankofaIdentity
import dev.sankofa.sdk.util.SankofaLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SankofaIdentityTest {

    private lateinit var context: Context
    private lateinit var identity: SankofaIdentity
    private val logger = SankofaLogger(debug = false)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clear shared prefs between tests
        context.getSharedPreferences("sankofa_identity", Context.MODE_PRIVATE)
            .edit().clear().commit()
        identity = SankofaIdentity(context, logger)
    }

    @Test
    fun `generates anonymous id on first launch`() {
        val anonId = identity.anonymousId
        assertNotNull(anonId)
        assert(anonId.isNotBlank())
    }

    @Test
    fun `anonymous id persists across instantiations`() {
        val firstId = identity.anonymousId
        val identity2 = SankofaIdentity(context, logger)
        assertEquals(firstId, identity2.anonymousId)
    }

    @Test
    fun `distinctId is anonymous id before identify`() {
        assertNull(identity.userId)
        assertEquals(identity.anonymousId, identity.distinctId)
    }

    @Test
    fun `identify sets userId and returns alias event`() {
        val aliasEvent = identity.identify("user_123")
        assertNotNull(aliasEvent)
        assertEquals("alias", aliasEvent!!["type"])
        assertEquals("user_123", aliasEvent["distinct_id"])
        assertEquals("user_123", identity.distinctId)
        assertEquals("user_123", identity.userId)
    }

    @Test
    fun `identify returns null when same userId`() {
        identity.identify("user_123")
        val aliasEvent = identity.identify("user_123")
        assertNull(aliasEvent)
    }

    @Test
    fun `reset clears userId and generates new anonymous id`() {
        identity.identify("user_123")
        val oldAnonId = identity.anonymousId
        identity.reset()
        assertNull(identity.userId)
        assertNotEquals(oldAnonId, identity.anonymousId)
    }
}
