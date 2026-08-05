package com.webanywhere.streamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buffer floor.
 *
 * A player cannot hold less media than the segment it is currently playing, so
 * a buffer below one segment is not a smaller cushion — it is a gap, and the
 * media that falls in it is simply never held by anyone.
 */
class BufferFloorTest {

    private fun config(buffer: Buffer, latency: Latency) =
        StreamConfig(buffer = buffer, latency = latency)

    @Test
    fun `the floor is one and a half segments`() {
        assertEquals(150, config(Buffer.MS150, Latency.INSTANT).minBufferMs)
        assertEquals(375, config(Buffer.MS150, Latency.BALANCED).minBufferMs)
        assertEquals(750, config(Buffer.MS150, Latency.RELAXED).minBufferMs)
    }

    @Test
    fun `150 ms is honoured exactly at 100 ms segments`() {
        val config = config(Buffer.MS150, Latency.INSTANT)

        assertEquals(150, config.effectiveBufferMs)
        assertFalse(config.bufferRaised)
    }

    @Test
    fun `a request below the floor is raised to it`() {
        // 150 ms asked for, but each segment is 500 ms: the player would be
        // told to hold less than a third of what it is playing.
        val config = config(Buffer.MS150, Latency.RELAXED)

        assertEquals(750, config.effectiveBufferMs)
        assertTrue(config.bufferRaised)
    }

    @Test
    fun `comfortable buffers are left alone`() {
        for (latency in Latency.entries) {
            val config = config(Buffer.SMOOTH, latency)
            assertEquals("latency=$latency", 2_000, config.effectiveBufferMs)
            assertFalse("latency=$latency", config.bufferRaised)
        }
    }

    @Test
    fun `the effective buffer is never below one segment for any combination`() {
        for (buffer in Buffer.entries) {
            for (latency in Latency.entries) {
                val config = config(buffer, latency)
                assertTrue(
                    "buffer=$buffer latency=$latency gave ${config.effectiveBufferMs}",
                    config.effectiveBufferMs > latency.segmentMs,
                )
            }
        }
    }

    @Test
    fun `the retained window always covers the effective buffer`() {
        for (buffer in Buffer.entries) {
            for (latency in Latency.entries) {
                val config = config(buffer, latency)
                val retainedMs = config.retainedSegments * latency.segmentMs
                // Twice over, because a client joins inside the window rather
                // than at its edge; retaining exactly one buffer's worth would
                // mean the oldest segment expires as it is needed.
                assertTrue(
                    "buffer=$buffer latency=$latency retains ${retainedMs}ms " +
                        "for a ${config.effectiveBufferMs}ms buffer",
                    retainedMs >= config.effectiveBufferMs * 2,
                )
            }
        }
    }

    @Test
    fun `raising the latency carries a stranded buffer up with it`() {
        // 150 ms is valid at 100 ms segments and impossible at 500 ms ones.
        val tight = config(Buffer.MS150, Latency.INSTANT)
        val relaxed = tight.withLatency(Latency.RELAXED)

        // Moved to the smallest option that still fits, not left selected on a
        // value the UI would show as disabled.
        assertEquals(Buffer.TIGHT, relaxed.buffer)
        assertFalse(relaxed.bufferRaised)
    }

    @Test
    fun `lowering the latency leaves the choice alone`() {
        val relaxed = config(Buffer.SMOOTH, Latency.RELAXED)

        assertEquals(Buffer.SMOOTH, relaxed.withLatency(Latency.INSTANT).buffer)
    }

    @Test
    fun `no latency change can strand the selection below the floor`() {
        for (buffer in Buffer.entries) {
            for (from in Latency.entries) {
                for (to in Latency.entries) {
                    val moved = config(buffer, from).withLatency(to)
                    assertTrue(
                        "$buffer $from -> $to left ${moved.buffer} below ${moved.minBufferMs}",
                        moved.buffer.ms >= moved.minBufferMs,
                    )
                }
            }
        }
    }

    @Test
    fun `bufferUs agrees with the effective buffer`() {
        val config = config(Buffer.MS150, Latency.RELAXED)
        assertEquals(config.effectiveBufferMs * 1_000L, config.bufferUs)
    }
}
