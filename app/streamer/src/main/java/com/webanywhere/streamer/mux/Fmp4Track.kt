package com.webanywhere.streamer.mux

/**
 * Turns a stream of encoded frames into a stream of fMP4 media segments.
 *
 * Two duration strategies, because video and audio need different ones:
 *
 *  - **Video** (`fixedSampleDurationTicks == null`): a sample's duration is the
 *    gap to the *next* sample, so one frame is always held back until its
 *    successor arrives.
 *  - **Audio**: every AAC frame is exactly 1024 samples, so durations are known
 *    up front, nothing is held back, and segments are cut on a duration target.
 *
 * Segment length is deliberately decoupled from key frame cadence, and that is
 * the single biggest lever on end-to-end latency. Cutting only on key frames
 * ties the segment length to the GOP: one second of key frame interval means a
 * second of latency floor, because nothing can be sent until the next key frame
 * closes the segment. Cutting on a duration target instead lets segments be
 * ~100 ms while key frames stay a second apart — cheap to encode, quick to
 * deliver. The cost is that not every segment is a valid entry point, so each
 * one records whether it starts with a key frame and joining clients are sent
 * to the most recent one that does.
 *
 * Not thread-safe: drive each instance from a single encoder thread.
 */
internal class Fmp4Track(
    private val trackId: Int,
    private val timescale: Int,
    private val targetSegmentUs: Long,
    private val fixedSampleDurationTicks: Int? = null,
    private val onSegment: (
        sequence: Long,
        data: ByteArray,
        durationUs: Long,
        startsWithSync: Boolean,
    ) -> Unit,
) {

    private class Held(val data: ByteArray, val ptsUs: Long, val isSync: Boolean)

    private val pending = ArrayList<Fmp4.Sample>(64)
    private var held: Held? = null
    private var sequence = 0L
    private var baseDecodeTicks = 0L
    private var pendingTicks = 0L
    private var segmentStartUs = -1L
    private var pendingStartsWithSync = true

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
        if (pending.isEmpty()) pendingStartsWithSync = isSync
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
        if (pending.isEmpty()) pendingStartsWithSync = previous.isSync
        pending.add(Fmp4.Sample(previous.data, ticks, previous.isSync))
        pendingTicks += ticks
        sampleCount++

        // Two reasons to cut. The key frame case matters most: cutting *before*
        // the key frame we are about to write means the next segment starts
        // with it, which is what makes it a valid entry point for a client that
        // has just connected.
        val spanUs = ticksToUs(pendingTicks)
        if (isSync || spanUs >= targetSegmentUs) emit(ptsUs)
    }

    /** Flushes whatever is buffered. Call on stop, never mid-stream. */
    fun flush() {
        held?.let {
            val ticks = usToTicks(NOMINAL_LAST_FRAME_US).toInt().coerceAtLeast(1)
            if (pending.isEmpty()) pendingStartsWithSync = it.isSync
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
        pendingStartsWithSync = true
        sampleCount = 0L
    }

    private fun emit(nextStartUs: Long) {
        if (pending.isEmpty()) return

        val data = Fmp4.segment(sequence, trackId, baseDecodeTicks, pending)
        val durationUs = ticksToUs(pendingTicks)
        onSegment(sequence, data, durationUs, pendingStartsWithSync)

        sequence++
        baseDecodeTicks += pendingTicks
        pendingTicks = 0
        pending.clear()
        pendingStartsWithSync = true
        segmentStartUs = if (nextStartUs >= 0) nextStartUs else -1L
    }

    private fun usToTicks(us: Long): Long = us * timescale / 1_000_000L

    private fun ticksToUs(ticks: Long): Long = ticks * 1_000_000L / timescale

    private companion object {
        /** The final frame has no successor to measure against; ~30 fps will do. */
        const val NOMINAL_LAST_FRAME_US = 33_333L
    }
}
