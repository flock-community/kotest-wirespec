package io.kotest.extensions.spring.wirespec

import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.spring.wirespec.spring.WebClientTransportation
import org.springframework.web.reactive.function.client.WebClient

/**
 * Framework-neutral handle the scenario DSL consumes: a [Wirespec.Transportation]
 * for sending requests and a [Wirespec.Serialization] for typed (de)serialization.
 *
 * Build it directly when you already have both halves (e.g. wired via Spring
 * beans), or use [http] to spin up a [WebClient]-backed transport against a
 * running app's base URL.
 */
class WirespecTestContext(
    val transportation: Wirespec.Transportation,
    val serialization: Wirespec.Serialization,
) {
    companion object {
        /**
         * Build a context backed by Spring's `WebClient` for HTTP-based integration
         * tests (e.g. against an app booted by `@SpringBootTest(webEnvironment = RANDOM_PORT)`).
         */
        fun http(baseUrl: String, serialization: Wirespec.Serialization): WirespecTestContext =
            WirespecTestContext(
                transportation = WebClientTransportation(WebClient.create(baseUrl)),
                serialization = serialization,
            )
    }
}
