package dev.sankofa.sdk.replay

import android.graphics.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComposeMaskRegistryTest {

    @After
    fun tearDown() {
        // Each test starts from a clean slate.
        ComposeMaskRegistry.snapshot().forEach { /* no-op read */ }
        clear()
    }

    private fun clear() {
        // Remove whatever the test registered (tokens are local objects).
        registered.forEach { ComposeMaskRegistry.remove(it) }
        registered.clear()
    }

    private val registered = mutableListOf<Any>()
    private fun token() = Any().also { registered.add(it) }

    @Test
    fun `update then snapshot returns the rect`() {
        val t = token()
        ComposeMaskRegistry.update(t, Rect(10, 20, 110, 80))

        val snap = ComposeMaskRegistry.snapshot()
        assertTrue(snap.contains(Rect(10, 20, 110, 80)))
    }

    @Test
    fun `update replaces the rect for the same token (moved field)`() {
        val t = token()
        ComposeMaskRegistry.update(t, Rect(0, 0, 50, 50))
        ComposeMaskRegistry.update(t, Rect(100, 100, 200, 160))

        val snap = ComposeMaskRegistry.snapshot()
        assertEquals(1, snap.count { it.left == 100 || it.left == 0 })
        assertTrue(snap.contains(Rect(100, 100, 200, 160)))
    }

    @Test
    fun `remove drops the rect (node left composition)`() {
        val t = token()
        ComposeMaskRegistry.update(t, Rect(0, 0, 10, 10))
        ComposeMaskRegistry.remove(t)

        assertTrue(ComposeMaskRegistry.snapshot().none { it == Rect(0, 0, 10, 10) })
    }

    @Test
    fun `multiple tokens coexist`() {
        val a = token()
        val b = token()
        ComposeMaskRegistry.update(a, Rect(0, 0, 10, 10))
        ComposeMaskRegistry.update(b, Rect(20, 20, 30, 30))

        val snap = ComposeMaskRegistry.snapshot()
        assertTrue(snap.contains(Rect(0, 0, 10, 10)))
        assertTrue(snap.contains(Rect(20, 20, 30, 30)))
    }
}
