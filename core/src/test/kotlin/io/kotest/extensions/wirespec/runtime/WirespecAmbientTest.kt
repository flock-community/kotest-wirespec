package io.kotest.extensions.wirespec.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.v2.kotlin.WirespecSerialization
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.wirespec.WirespecTestContext
import io.kotest.extensions.wirespec.useWirespecSeed
import io.kotest.extensions.wirespec.withWirespec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class WirespecAmbientTest : FunSpec({

    val serialization: Wirespec.Serialization = WirespecSerialization(jacksonObjectMapper())
    val noopHttp = object : Wirespec.Transportation {
        override suspend fun transport(request: Wirespec.RawRequest): Wirespec.RawResponse =
            error("no transport in this test")
    }

    test("currentAmbient outside any ambient throws a helpful error") {
        val ex = runCatching { currentAmbient() }.exceptionOrNull()
            ?: error("expected an error")
        ex.message!! shouldContain "No wirespec ambient context"
    }

    test("withWirespec installs an ambient whose endpointContext is the explicit override") {
        val ctx = WirespecTestContext(noopHttp, serialization)
        withWirespec(ctx, seed = 7L) {
            val ambient = currentAmbient()
            ambient.endpointContext() shouldBe ctx
            ambient.rng.seed shouldBe 7L
        }
    }

    test("useWirespecSeed rebinds the ambient RandomSource to the checkAll iteration source") {
        val ctx = WirespecTestContext(noopHttp, serialization)
        val seenSeeds = mutableListOf<Long>()
        withWirespec(ctx, seed = 1L) {
            io.kotest.property.checkAll<Int>(iterations = 3) {
                useWirespecSeed()
                seenSeeds += currentAmbient().rng.seed
            }
        }
        // After binding, the ambient seed is no longer the fixed 1L for every iteration.
        seenSeeds.size shouldBe 3
        seenSeeds.any { it != 1L } shouldBe true
    }
})
