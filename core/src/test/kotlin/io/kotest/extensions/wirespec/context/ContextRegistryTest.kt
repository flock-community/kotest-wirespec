package io.kotest.extensions.wirespec.context

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain

class ContextRegistryTest : FunSpec({

    test("ContextRegistry discovers providers registered via ServiceLoader") {
        // The core test classpath registers FakeEndpointContextProvider via
        // META-INF/services; confirm the loader finds it without throwing.
        val providers = ContextRegistry.providers
        providers.map { it::class } shouldContain FakeEndpointContextProvider::class
    }
})
