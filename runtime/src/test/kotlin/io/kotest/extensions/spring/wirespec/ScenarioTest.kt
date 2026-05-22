package io.kotest.extensions.spring.wirespec

import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import community.flock.wirespec.kotlin.Wirespec
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll
import java.util.concurrent.atomic.AtomicInteger

class ScenarioTest : FunSpec({

    val noopTransport = object : Wirespec.Transportation {
        override suspend fun transport(request: Wirespec.RawRequest): Wirespec.RawResponse =
            error("no scenario step should call transport in this test")
    }
    val serialization: Wirespec.Serialization = WirespecSerialization(jacksonObjectMapper())
    val ctx = WirespecTestContext(noopTransport, serialization)

    test("scenario with no calls runs cleanly inside checkAll") {
        val iterations = 5
        val counter = AtomicInteger(0)

        checkAll<Int>(iterations = iterations) {
            scenario(ctx) {
                // intentionally empty: no endpoint calls registered
                counter.incrementAndGet()
            }
        }

        counter.get() shouldBe iterations
    }

    test("scenario with no calls runs cleanly with the seed overload") {
        var ran = false
        scenario(ctx, seed = 1234L) {
            ran = true
        }
        ran shouldBe true
    }
})
