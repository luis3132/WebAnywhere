package com.webanywhere.streamer.stream

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the invariant PLAN.md calls the most important regression in the
 * project: **a slow client must not slow anybody else down.**
 *
 * If these ever start failing, the streaming quality bug they describe will be
 * invisible on a desk and obvious in a moving car.
 */
class BackpressureTest {

    @Test
    fun `publishing never blocks on a client that is not collecting`() {
        val hub = MjpegHub()
        val idle = hub.subscribe()

        // A thousand frames into a subscriber that never calls next(). If this
        // blocked or threw, the capture pipeline would stall behind one client.
        repeat(1_000) { index -> hub.publish(byteArrayOf(index.toByte())) }

        assertTrue("slow client should have accumulated drops", idle.droppedFrames > 900)
    }

    @Test
    fun `a stalled client does not starve a healthy one`() = runBlocking {
        val hub = MjpegHub()
        val stalled = hub.subscribe()
        val healthy = hub.subscribe()

        var delivered = 0
        repeat(50) { index ->
            hub.publish(byteArrayOf(index.toByte()))
            // The healthy client drains every frame; the stalled one never does.
            delivered += healthy.next().size
        }

        assertEquals("healthy client missed frames", 50, delivered)
        assertTrue("stalled client should be dropping", stalled.droppedFrames > 40)
    }

    @Test
    fun `a conflated client always receives the newest frame, never a stale one`() = runBlocking {
        val hub = MjpegHub()
        val subscriber = hub.subscribe()

        hub.publish(byteArrayOf(1))
        hub.publish(byteArrayOf(2))
        hub.publish(byteArrayOf(3))

        // Catching up must mean jumping to the present, not replaying a backlog.
        assertEquals(3, subscriber.next()[0].toInt())
    }

    @Test
    fun `an evicted segment reports Gone so the client resynchronises`() = runBlocking {
        val ring = SegmentRing(capacity = 3)
        repeat(6) { index ->
            ring.add(SegmentRing.Segment(index.toLong(), byteArrayOf(index.toByte()), 1_000_000L))
        }

        // Sequence 0 fell out of the window: chasing it would never converge.
        assertEquals(SegmentRing.Result.Gone, ring.await(0, timeoutMs = 100))

        val current = ring.await(5, timeoutMs = 100)
        assertTrue(current is SegmentRing.Result.Ready)
        assertEquals(5L, (current as SegmentRing.Result.Ready).segment.sequence)
    }

    @Test
    fun `awaiting a future segment parks instead of spinning, then times out`() = runBlocking {
        val ring = SegmentRing(capacity = 3)
        ring.add(SegmentRing.Segment(0, byteArrayOf(0), 1_000_000L))

        val started = System.currentTimeMillis()
        val result = ring.await(7, timeoutMs = 250)
        val elapsed = System.currentTimeMillis() - started

        assertEquals(SegmentRing.Result.Timeout, result)
        assertTrue("await should have parked for the full timeout", elapsed >= 200)
    }
}
