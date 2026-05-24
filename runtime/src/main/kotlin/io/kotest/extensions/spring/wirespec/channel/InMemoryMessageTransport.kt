package io.kotest.extensions.spring.wirespec.channel

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Deterministic fake [MessageTransport] for runtime unit tests.
 *
 *  - `publish` appends to a thread-safe in-memory log.
 *  - `receive` filters by topic, returning as soon as `atLeast` records have
 *    accumulated *or* `within` has elapsed. Polls the log at 10 ms intervals.
 *  - A per-instance `receiverCursor` advances after each `receive()` call so
 *    sequential receives on the same transport don't re-see old records.
 *    The runner uses a fresh transport per scenario, but this keeps the fake
 *    honest when reused.
 */
class InMemoryMessageTransport : MessageTransport {

    private val log: MutableList<IncomingRecord> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile private var receiverCursor: Int = 0

    override suspend fun publish(record: OutgoingRecord) {
        log += IncomingRecord(record.topic, record.key, record.body)
    }

    override suspend fun receive(topic: String, atLeast: Int, within: Duration): List<IncomingRecord> {
        val mark = TimeSource.Monotonic.markNow()
        val collected = mutableListOf<IncomingRecord>()
        var cursor = receiverCursor
        while (collected.size < atLeast && mark.elapsedNow() < within) {
            synchronized(log) {
                while (cursor < log.size) {
                    val r = log[cursor]
                    cursor++
                    if (r.topic == topic) collected += r
                }
            }
            if (collected.size >= atLeast) break
            delay(10.milliseconds)
        }
        receiverCursor = cursor
        return collected.toList()
    }

    /** Snapshot of all published records, for debugging in tests. */
    fun published(): List<OutgoingRecord> = synchronized(log) {
        log.map { OutgoingRecord(it.topic, it.key, it.body) }
    }
}
