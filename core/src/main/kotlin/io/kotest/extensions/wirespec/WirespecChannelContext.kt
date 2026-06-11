package io.kotest.extensions.wirespec

import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.wirespec.channel.MessageTransport

/**
 * Framework-neutral handle for the channel half of the scenario DSL: a
 * [MessageTransport] for publishing/receiving raw records and a
 * [Wirespec.Serialization] for typed (de)serialization of channel payloads.
 *
 * Build it directly when you already have both halves, or — for an
 * EmbeddedKafka-backed Spring test — let the spring [ContextProvider]
 * [io.kotest.extensions.wirespec.context.ContextProvider] auto-resolve one for
 * `scenario { … }`.
 */
class WirespecChannelContext(
    val messaging: MessageTransport,
    val serialization: Wirespec.Serialization,
)
