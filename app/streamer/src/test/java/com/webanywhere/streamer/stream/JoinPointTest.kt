package com.webanywhere.streamer.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a joining client is told to start.
 *
 * The rule is not "the newest key frame": with a buffer configured, a client
 * that starts at the live edge has an empty cushion and stalls on the first
 * hiccup. It has to start deliberately behind, and only on a key frame.
 */
class JoinPointTest {

    /** Segments of 100 ms, a key frame every fifth one. */
    private fun ring(count: Int, capacity: Int = 100): SegmentRing {
        val ring = SegmentRing(capacity)
        for (i in 0 until count) {
            ring.add(
                SegmentRing.Segment(
                    sequence = i.toLong(),
                    data = ByteArray(10),
                    durationUs = 100_000L,
                    isSync = i % 5 == 0,
                ),
            )
        }
        return ring
    }

    @Test
    fun `join point leaves at least the requested buffer behind it`() {
        val ring = ring(count = 40)

        // Segments 0..39 exist; the newest is 39. A 1 s buffer needs 10
        // segments after the entry point, so the entry point can be no newer
        // than 29 — and must be a key frame, so 25.
        val join = ring.joinPointFor(bufferUs = 1_000_000L)

        assertEquals(25L, join)
        assertEquals(0L, join % 5)
    }

    @Test
    fun `a bigger buffer joins further back`() {
        val ring = ring(count = 40)

        val tight = ring.joinPointFor(bufferUs = 500_000L)
        val wide = ring.joinPointFor(bufferUs = 2_000_000L)

        assert(wide < tight) { "expected $wide to be older than $tight" }
    }

    @Test
    fun `falls back to the oldest entry point when the window is too short`() {
        // Only 8 segments retained but 5 s asked for: there is nowhere near
        // enough history. Less cushion than requested beats no picture.
        val ring = ring(count = 8)

        assertEquals(0L, ring.joinPointFor(bufferUs = 5_000_000L))
    }

    @Test
    fun `reports nothing when the ring is empty`() {
        assertEquals(-1L, SegmentRing(10).joinPointFor(bufferUs = 1_000_000L))
    }

    @Test
    fun `never returns a segment that starts mid-GOP`() {
        val ring = ring(count = 40)

        // Every buffer size, from none to more than the window holds.
        for (ms in 0..4_000 step 100) {
            val join = ring.joinPointFor(bufferUs = ms * 1_000L)
            assertEquals("buffer=${ms}ms landed mid-GOP at $join", 0L, join % 5)
        }
    }

    @Test
    fun `resize evicts down to the new capacity`() {
        val ring = ring(count = 40)
        ring.resize(capacity = 10, maxBytes = Int.MAX_VALUE)

        assertEquals(10, ring.size)
        assertEquals(30L, ring.oldest)
    }

    @Test
    fun `the byte cap wins over the segment count`() {
        val ring = SegmentRing(capacity = 100, maxBytes = 1_000)
        for (i in 0 until 20) {
            ring.add(
                SegmentRing.Segment(
                    sequence = i.toLong(),
                    data = ByteArray(300),
                    durationUs = 100_000L,
                    isSync = true,
                ),
            )
        }

        // 1000 bytes at 300 each: three fit, and the cap is what bounds it,
        // not the capacity of 100.
        assertEquals(3, ring.size)
    }
}
