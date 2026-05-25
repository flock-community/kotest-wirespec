package io.kotest.extensions.wirespec.spring

import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.wirespec.WirespecTestContext
import org.springframework.web.reactive.function.client.WebClient

/**
 * Spring `WebClient`-backed factory for HTTP-based integration tests
 * (e.g. against an app booted by `@SpringBootTest(webEnvironment = RANDOM_PORT)`).
 *
 * Replaces the old `WirespecTestContext.http(...)` companion factory, which
 * pulled Spring onto the core classpath; the function now lives in the
 * `kotest-wirespec-spring` module instead.
 */
fun WirespecTestContext.Companion.http(
    baseUrl: String,
    serialization: Wirespec.Serialization,
): WirespecTestContext =
    WirespecTestContext(
        transportation = WebClientTransportation(WebClient.create(baseUrl)),
        serialization = serialization,
    )
