package io.kotest.extensions.wirespec.example.support

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.v2.kotlin.WirespecSerialization
import community.flock.wirespec.integration.kotest.WirespecChannelContext
import community.flock.wirespec.integration.kotest.WirespecTestContext
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.wirespec.example.ExampleApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker

/**
 * Process-wide test environment shared by every scenario spec: one in-JVM Kafka broker
 * and one Spring app instance (on a random port, pointed at that broker), started lazily
 * on first use and torn down by a JVM shutdown hook.
 *
 * The endpoint and channel transport contexts it exposes are what [ScenarioContextProvider]
 * hands to the generated `*.call { … }` DSL, so specs only need
 * `@ApplyExtension(WirespecExtension::class)` — no base class.
 */
object PetTestEnvironment {

    const val EVENTS_TOPIC: String = "pets.events"
    const val COMMANDS_TOPIC: String = "pets.commands"

    private val broker: EmbeddedKafkaKraftBroker by lazy {
        EmbeddedKafkaKraftBroker(1, 1, EVENTS_TOPIC, COMMANDS_TOPIC).apply { afterPropertiesSet() }
    }

    /** The running application context, for asserting against beans if needed. */
    val application: ConfigurableApplicationContext by lazy {
        val context = SpringApplicationBuilder(ExampleApplication::class.java)
            .properties(
                "server.port=0",
                "spring.kafka.bootstrap-servers=${broker.brokersAsString}",
            )
            .run()
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { channelTransport.close() }
                runCatching { context.close() }
                runCatching { broker.destroy() }
            },
        )
        context
    }

    // The example app has no Wirespec.Serialization bean (plain Spring MVC), so build a
    // Jackson-backed one here — it is what the DSL uses to (de)serialize bodies/events.
    private val serialization: Wirespec.Serialization = WirespecSerialization(jacksonObjectMapper())

    private val channelTransport: KafkaChannelTransport by lazy {
        KafkaChannelTransport(broker.brokersAsString, listOf(EVENTS_TOPIC, COMMANDS_TOPIC))
    }

    val endpointContext: WirespecTestContext by lazy {
        val port = (application as ServletWebServerApplicationContext).webServer.port
        WirespecTestContext(
            transportation = HttpClientTransportation("http://localhost:$port"),
            serialization = serialization,
        )
    }

    val channelContext: WirespecChannelContext by lazy {
        WirespecChannelContext(
            transport = channelTransport,
            serialization = serialization,
            defaultTopic = EVENTS_TOPIC,
        )
    }

    /**
     * Reposition every shared channel consumer at the current log end, so a scenario's
     * `expecting`/`collecting` only observe events published *during that scenario*. Call
     * from `beforeEach { }` in channel specs.
     */
    fun watchChannelsFromNow() {
        channelTransport.seekToEnd()
    }
}
