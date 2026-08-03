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
class SegmentRing(private val capacity: Int, private val maxBytes: Int = Int.MAX_VALUE) {

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

            // Bounded by count *and* by bytes: at an uncapped bitrate a segment
            // can be an order of magnitude larger than expected, and a
            // count-only bound would quietly turn into tens of megabytes.
            while (items.size > capacity || (bytes > maxBytes && items.size > 2)) {
                bytes -= items.removeFirst().data.size
            }
            oldest = items.first().sequence

            if (segment.isSync) latestSync = segment.sequence
            if (latestSync < oldest) {
                latestSync = items.firstOrNull { it.isSync }?.sequence ?: -1L
            }
        }
        _latest.value = segment.sequence
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
