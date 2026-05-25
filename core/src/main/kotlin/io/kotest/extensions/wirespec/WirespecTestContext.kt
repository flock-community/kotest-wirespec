package io.kotest.extensions.wirespec

import community.flock.wirespec.kotlin.Wirespec

/**
 * Framework-neutral handle the scenario DSL consumes: a [Wirespec.Transportation]
 * for sending requests and a [Wirespec.Serialization] for typed (de)serialization.
 *
 * Build it directly when you already have both halves. For the Spring
 * `WebClient`-backed factory, see
 * `io.kotest.extensions.wirespec.spring.http(baseUrl, serialization)` in the
 * `kotest-wirespec-spring` module.
 */
class WirespecTestContext(
    val transportation: Wirespec.Transportation,
    val serialization: Wirespec.Serialization,
) {
    /**
     * Empty companion left in place so framework modules (e.g.
     * `kotest-wirespec-spring`) can hang convenience factory extensions
     * off `WirespecTestContext.Companion`.
     */
    companion object
}
