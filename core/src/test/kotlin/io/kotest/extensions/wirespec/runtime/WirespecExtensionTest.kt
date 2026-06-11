package io.kotest.extensions.wirespec.runtime

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.wirespec.WirespecExtension
import io.kotest.matchers.shouldBe

@ApplyExtension(WirespecExtension::class)
class WirespecExtensionTest : FunSpec({

    test("WirespecExtension installs an ambient visible inside the test body") {
        val ambient = currentAmbient()
        // currentAmbient() did not throw → the extension installed the element.
        ambient.rng.randomSource.seed shouldBe ambient.rng.seed
    }
})
