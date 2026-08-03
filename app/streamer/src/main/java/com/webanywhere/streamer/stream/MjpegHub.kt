package com.webanywhere.streamer.stream

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Fans JPEG frames out to every connected MJPEG client.
 *
 * The important property here is the one PLAN.md calls failure mode #1:
 * **a slow client must never slow down the encoder.** Each subscriber gets a
 * conflated channel — capacity 1, oldest dropped on overflow — so publishing is
 * a non-blocking `trySend` that either hands over the newest frame or silently
 * replaces the one the client had not collected yet.
 *
 * A client on a weak signal therefore sees a lower frame rate. Everyone else,
 * and the capture pipeline itself, runs at full speed.
 */
class MjpegHub {

    class Subscriber internal constructor(val id: Long) {

        internal val channel = Channel<ByteArray>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        private val offered = AtomicLong(0)
        private val received = AtomicLong(0)

        suspend fun next(): ByteArray = channel.receive().also { received.incrementAndGet() }

        internal fun offer(frame: ByteArray) {
            offered.incrementAndGet()
            // Always succeeds on a DROP_OLDEST channel, and never blocks. A
            // failure means the channel is closed, i.e. the client is already
            // on its way out, which is not the encoder's problem.
            channel.trySend(frame)
        }

        /**
         * A `DROP_OLDEST` channel discards silently, so `trySend` cannot report
         * a drop — counting its failures would report zero for ever. The real
         * figure is what was offered minus what was collected, less the one
         * frame that may legitimately be sitting in the buffer.
         */
        val droppedFrames: Long
            get() = (offered.get() - received.get() - 1).coerceAtLeast(0)

        val deliveredFrames: Long get() = received.get()
    }

    private val subscribers = CopyOnWriteArrayList<Subscriber>()
    private val nextId = AtomicLong(1)
    private val _clientCount = MutableStateFlow(0)

    val clientCount: StateFlow<Int> = _clientCount

    /** Newest frame, so a client connecting mid-stream sees something at once. */
    @Volatile
    private var lastFrame: ByteArray? = null

    fun subscribe(): Subscriber {
        val subscriber = Subscriber(nextId.getAndIncrement())
        subscribers.add(subscriber)
        _clientCount.value = subscribers.size
        lastFrame?.let { subscriber.offer(it) }
        return subscriber
    }

    fun unsubscribe(subscriber: Subscriber) {
        if (subscribers.remove(subscriber)) {
            _clientCount.value = subscribers.size
            subscriber.channel.close()
        }
    }

    fun publish(frame: ByteArray) {
        lastFrame = frame
        for (subscriber in subscribers) subscriber.offer(frame)
    }

    /** Total frames dropped across all current subscribers. */
    fun droppedFrames(): Long = subscribers.sumOf { it.droppedFrames }

    fun reset() {
        lastFrame = null
    }
}
