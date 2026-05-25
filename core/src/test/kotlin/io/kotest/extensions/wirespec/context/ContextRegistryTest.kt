package io.kotest.extensions.wirespec.context

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ContextRegistryTest : FunSpec({

    test("ContextRegistry returns the providers registered via ServiceLoader") {
        // No spring module on the test classpath, so there should be exactly zero
        // providers discovered. Confirm the loader does not throw.
        val providers = ContextRegistry.providers
        providers.size shouldBe 0
    }
})
