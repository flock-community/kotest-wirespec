package io.kotest.extensions.spring.wirespec.validation

import community.flock.wirespec.kotlin.Wirespec

/**
 * Mirrors [ContractValidator] for channels — minus the status check.
 * Channels carry a single payload type, so validation is just:
 * "do the bytes deserialize into the declared payload?"
 */
internal class ChannelValidator(
    private val reflection: ChannelReflection,
    private val serialization: Wirespec.Serialization,
) {
    fun deserialize(body: ByteArray): Any {
        return try {
            // Explicit <Any>: without it, Kotlin infers T = Nothing (most-specific subtype),
            // and Wirespec's `raw as T` cast throws KotlinNothingValueException.
            serialization.deserializeBody<Any>(body, reflection.payloadType)
        } catch (t: Throwable) {
            throw ChannelViolation(
                channel = reflection.channelName,
                message = "payload did not match ${reflection.payloadType}: ${t.message ?: t::class.simpleName}",
                rawBody = body,
                cause = t,
            )
        }
    }
}

class ChannelViolation internal constructor(
    val channel: String,
    message: String,
    val rawBody: ByteArray?,
    cause: Throwable? = null,
) : AssertionError(
    buildString {
        append("[$channel] ")
        append(message)
        rawBody?.let { body ->
            append("\n  body=")
            append(String(body).take(2048))
        }
    },
    cause,
)
