package com.webanywhere.streamer.mux

/**
 * Turns a stream of encoded frames into a stream of fMP4 media segments.
 *
 * Two duration strategies, because video and audio need different ones:
 *
 *  - **Video** (`fixedSampleDurationTicks == null`): a sample's duration is the
 *    gap to the *next* sample, so one frame is always held back until its
 *    successor arrives. Segments are cut on key frames only, so every segment
 *    is independently decodable and a newly-arrived client can start on any of
 *    them.
 *  - **Audio**: every AAC frame is exactly 1024 samples, so durations are known
 *    up front, nothing is held back, and segments are cut on a duration target.
 *
 * Not thread-safe: drive each instance from a single encoder thread.
 */
internal class Fmp4Track(
    private val trackId: Int,
    private val timescale: Int,
    private val targetSegmentUs: Long,
    private val fixedSampleDurationTicks: Int? = null,
    private val onSegment: (sequence: Long, data: ByteArray, durationUs: Long) -> Unit,
) {

    private class Held(val data: ByteArray, val ptsUs: Long, val isSync: Boolean)

    private val pending = ArrayList<Fmp4.Sample>(64)
    private var held: Held? = null
    private var sequence = 0L
    private var baseDecodeTicks = 0L
    private var pendingTicks = 0L
    private var segmentStartUs = -1L

    /** Frames emitted so far, for the stats readout. */
    var sampleCount = 0L
        private set

    fun push(data: ByteArray, ptsUs: Long, isSync: Boolean) {
        if (data.isEmpty()) return

        if (fixedSampleDurationTicks != null) {
            pushFixed(data, ptsUs, isSync)
        } else {
            pushDerived(data, ptsUs, isSync)
        }
    }

    private fun pushFixed(data: ByteArray, ptsUs: Long, isSync: Boolean) {
        if (segmentStartUs < 0) segmentStartUs = ptsUs
        pending.add(Fmp4.Sample(data, fixedSampleDurationTicks!!, isSync))
        pendingTicks += fixedSampleDurationTicks
        sampleCount++

        if (ticksToUs(pendingTicks) >= targetSegmentUs) emit(ptsUs)
    }

    private fun pushDerived(data: ByteArray, ptsUs: Long, isSync: Boolean) {
        val previous = held
        held = Held(data, ptsUs, isSync)

        if (previous == null) {
            if (segmentStartUs < 0) segmentStartUs = ptsUs
            return
        }

        // The previous frame's duration is only knowable now.
        val durationUs = (ptsUs - previous.ptsUs).coerceAtLeast(0L)
        val ticks = usToTicks(durationUs).toInt().coerceAtLeast(1)
        pending.add(Fmp4.Sample(previous.data, ticks, previous.isSync))
        pendingTicks += ticks
        sampleCount++

        // Cut on the key frame we are *about to* start writing, so the next
        // segment begins with it. A segment that starts mid-GOP is useless to a
        // client that just connected.
        val spanUs = ticksToUs(pendingTicks)
        if (isSync && pending.isNotEmpty() && spanUs >= minSegmentUs()) emit(ptsUs)
    }

    /** Flushes whatever is buffered. Call on stop, never mid-stream. */
    fun flush() {
        held?.let {
            val ticks = usToTicks(targetSegmentUs / 30).toInt().coerceAtLeast(1)
            pending.add(Fmp4.Sample(it.data, ticks, it.isSync))
            pendingTicks += ticks
            sampleCount++
            held = null
        }
        if (pending.isNotEmpty()) emit(-1L)
    }

    fun reset() {
        pending.clear()
        held = null
        sequence = 0L
        baseDecodeTicks = 0L
        pendingTicks = 0L
        segmentStartUs = -1L
        sampleCount = 0L
    }

    private fun emit(nextStartUs: Long) {
        if (pending.isEmpty()) return

        val data = Fmp4.segment(sequence, trackId, baseDecodeTicks, pending)
        val durationUs = ticksToUs(pendingTicks)
        onSegment(sequence, data, durationUs)

        sequence++
        baseDecodeTicks += pendingTicks
        pendingTicks = 0
        pending.clear()
        segmentStartUs = if (nextStartUs >= 0) nextStartUs else -1L
    }

    // A key frame arriving far sooner than the target (a scene cut forces one)
    // should not produce a flood of tiny segments; require at least half.
    private fun minSegmentUs(): Long = targetSegmentUs / 2

    private fun usToTicks(us: Long): Long = us * timescale / 1_000_000L

    private fun ticksToUs(ticks: Long): Long = ticks * 1_000_000L / timescale
}
