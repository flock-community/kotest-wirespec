package io.kotest.extensions.spring.wirespec.channel

import kotlin.time.Duration

/**
 * Raw-bytes messaging transport: parallel to [community.flock.wirespec.kotlin.Wirespec.Transportation]
 * but message-shaped instead of request/response-shaped. The runtime's
 * [community.flock.wirespec.kotlin.Wirespec.Serialization] handles typed
 * (de)serialization on top.
 *
 * `receive` returns as soon as `atLeast` records have been collected on
 * `topic`, OR `within` has elapsed — whichever happens first. Implementations
 * are responsible for honoring the timeout.
 */
interface MessageTransport {
    suspend fun publish(record: OutgoingRecord)
    suspend fun receive(
        topic: String,
        atLeast: Int,
        within: Duration,
    ): List<IncomingRecord>
}

data class OutgoingRecord(
    val topic: String,
    val key: String?,
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is OutgoingRecord &&
        topic == other.topic && key == other.key && body.contentEquals(other.body)
    override fun hashCode(): Int = (topic.hashCode() * 31 + (key?.hashCode() ?: 0)) * 31 + body.contentHashCode()
}

data class IncomingRecord(
    val topic: String,
    val key: String?,
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is IncomingRecord &&
        topic == other.topic && key == other.key && body.contentEquals(other.body)
    override fun hashCode(): Int = (topic.hashCode() * 31 + (key?.hashCode() ?: 0)) * 31 + body.contentHashCode()
}
