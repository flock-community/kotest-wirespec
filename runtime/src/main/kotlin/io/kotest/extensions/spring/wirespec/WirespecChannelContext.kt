package io.kotest.extensions.spring.wirespec

import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.spring.wirespec.channel.MessageTransport

/**
 * Framework-neutral handle for the channel half of the scenario DSL: a
 * [MessageTransport] for publishing/receiving raw records and a
 * [Wirespec.Serialization] for typed (de)serialization of channel payloads.
 *
 * Build it directly when you already have both halves, or — for an
 * EmbeddedKafka-backed Spring test — rely on
 * [SpringWirespecSpec.channelCtx]'s auto-resolution.
 */
class WirespecChannelContext(
    val messaging: MessageTransport,
    val serialization: Wirespec.Serialization,
)
