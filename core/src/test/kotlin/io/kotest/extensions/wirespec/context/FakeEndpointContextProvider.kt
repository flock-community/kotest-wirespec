package io.kotest.extensions.wirespec.context

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.core.spec.Spec
import io.kotest.extensions.wirespec.WirespecTestContext

/**
 * Test-only [ContextProvider] registered via `META-INF/services` so the core
 * suite can exercise `TestScope.scenario { … }`'s auto-resolution without the
 * spring module. Supplies a context whose transport is never hit (the scenarios
 * under test register no endpoint calls).
 */
class FakeEndpointContextProvider : ContextProvider {
    override fun endpointContext(spec: Spec): WirespecTestContext =
        WirespecTestContext(
            transportation = object : Wirespec.Transportation {
                override suspend fun transport(request: Wirespec.RawRequest): Wirespec.RawResponse =
                    error("no scenario step should call transport in this test")
            },
            serialization = WirespecSerialization(jacksonObjectMapper()),
        )
}
