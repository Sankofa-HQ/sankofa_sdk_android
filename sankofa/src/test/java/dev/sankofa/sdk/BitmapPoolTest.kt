package dev.sankofa.sdk

import dev.sankofa.sdk.replay.BitmapPool
import dev.sankofa.sdk.util.SankofaLogger
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BitmapPoolTest {

    private lateinit var pool: BitmapPool
    private val logger = SankofaLogger(debug = false)

    @Before
    fun setup() {
        pool = BitmapPool(logger, poolSize = 2)
    }

    @Test
    fun `acquire returns null before configure`() {
        val bitmap = pool.acquire()
        assertNull(bitmap)
    }

    @Test
    fun `acquire returns bitmap after configure`() {
        pool.configure(100, 200)
        val bitmap = pool.acquire()
        assertNotNull(bitmap)
        pool.release(bitmap!!)
    }

    @Test
    fun `pool drains after poolSize acquires`() {
        pool.configure(100, 200)
        val b1 = pool.acquire()
        val b2 = pool.acquire()
        val b3 = pool.acquire() // pool is now empty

        assertNotNull(b1)
        assertNotNull(b2)
        assertNull(b3)

        pool.release(b1!!)
        pool.release(b2!!)
    }

    @Test
    fun `released bitmap can be re-acquired`() {
        pool.configure(100, 200)
        val b1 = pool.acquire()!!
        pool.release(b1)
        val b2 = pool.acquire()
        assertNotNull(b2)
        pool.release(b2!!)
    }

    @Test
    fun `destroy recycles all bitmaps`() {
        pool.configure(100, 200)
        pool.destroy()
        val bitmap = pool.acquire()
        assertNull(bitmap)
    }
}
