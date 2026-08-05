package com.webanywhere.streamer.stream

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * A bounded, in-memory window of the most recent media segments.
 *
 * Segments never touch disk: at ~1 s each and a handful retained, the whole
 * live window is a few megabytes, and flash writes would only add latency and
 * wear.
 *
 * [await] is what makes the client loop cheap. Instead of polling for
 * "is segment N ready yet?", a request for a not-yet-produced segment parks on
 * the state flow until the encoder produces it. One request, one segment, no
 * spinning.
 */
class SegmentRing(capacity: Int, maxBytes: Int = Int.MAX_VALUE) {

    /**
     * Both bounds are mutable because they depend on settings the user changes
     * while the app is running: the buffer size decides how much history a
     * joining client needs, and the negotiated bitrate decides what that costs
     * in bytes. Neither is known when this object is constructed.
     */
    @Volatile
    private var capacity: Int = capacity

    @Volatile
    private var maxBytes: Int = maxBytes

    fun resize(capacity: Int, maxBytes: Int) {
        synchronized(lock) {
            this.capacity = capacity.coerceAtLeast(2)
            this.maxBytes = maxBytes
            trim()
        }
    }

    class Segment(
        val sequence: Long,
        val data: ByteArray,
        val durationUs: Long,
        /**
         * Whether this segment begins with a key frame. Only these are valid
         * entry points: a client that starts mid-GOP has nothing to decode
         * against and shows a green mess or nothing at all.
         */
        val isSync: Boolean = true,
    )

    private val lock = Any()
    private val items = ArrayDeque<Segment>(capacity)
    private var bytes = 0L

    private val _latest = MutableStateFlow(-1L)

    /** Sequence number of the newest segment, or -1 before anything is produced. */
    val latest: StateFlow<Long> = _latest

    /** Sequence number of the oldest segment still retained. */
    var oldest: Long = -1L
        private set

    /** Newest segment a fresh client can start from. -1 if there is none yet. */
    @Volatile
    var latestSync: Long = -1L
        private set

    val size: Int get() = synchronized(lock) { items.size }

    fun add(segment: Segment) {
        synchronized(lock) {
            items.addLast(segment)
            bytes += segment.data.size
            trim()

            if (segment.isSync) latestSync = segment.sequence
            if (latestSync < oldest) {
                latestSync = items.firstOrNull { it.isSync }?.sequence ?: -1L
            }
        }
        _latest.value = segment.sequence
    }

    /** Caller must hold [lock]. */
    private fun trim() {
        if (items.isEmpty()) return
        // Bounded by count *and* by bytes: at an uncapped bitrate a segment can
        // be an order of magnitude larger than expected, and a count-only bound
        // would quietly turn into tens of megabytes.
        while (items.size > capacity || (bytes > maxBytes && items.size > 2)) {
            bytes -= items.removeFirst().data.size
        }
        oldest = items.first().sequence
    }

    /**
     * The newest entry point that still leaves [bufferUs] of media behind it.
     *
     * A joining client needs its buffer filled from the start — starting at the
     * live edge with nothing in hand means the first hiccup is a stall. So it
     * joins deliberately behind, far enough back that the cushion exists on
     * frame one.
     *
     * Only key-frame segments qualify, and if the window does not hold enough
     * media yet this returns the deepest one available: less cushion than asked
     * for beats no picture.
     */
    fun joinPointFor(bufferUs: Long): Long = synchronized(lock) {
        var after = 0L
        var deepestSync = -1L
        for (i in items.indices.reversed()) {
            val segment = items[i]
            if (segment.isSync) {
                deepestSync = segment.sequence
                if (after >= bufferUs) return segment.sequence
            }
            after += segment.durationUs
        }
        deepestSync
    }

    fun get(sequence: Long): Segment? = synchronized(lock) {
        items.firstOrNull { it.sequence == sequence }
    }

    /** Everything currently retained, oldest first. Used to build the HLS playlist. */
    fun snapshot(): List<Segment> = synchronized(lock) { items.toList() }

    fun clear() {
        synchronized(lock) {
            items.clear()
            bytes = 0
            oldest = -1L
            latestSync = -1L
        }
        _latest.value = -1L
    }

    /**
     * Result of asking for a segment that may not exist yet.
     *
     * [Gone] means the client fell so far behind that the segment was evicted;
     * the only correct response is to resynchronise to the live edge rather
     * than keep chasing a window that is moving away faster than it downloads.
     */
    sealed interface Result {
        class Ready(val segment: Segment) : Result
        data object Gone : Result
        data object Timeout : Result
    }

    suspend fun await(sequence: Long, timeoutMs: Long): Result {
        get(sequence)?.let { return Result.Ready(it) }

        synchronized(lock) {
            if (oldest >= 0 && sequence < oldest) return Result.Gone
        }

        return try {
            withTimeout(timeoutMs) {
                latest.first { it >= sequence }
                get(sequence)?.let { Result.Ready(it) } ?: Result.Gone
            }
        } catch (_: TimeoutCancellationException) {
            Result.Timeout
        }
    }
}
