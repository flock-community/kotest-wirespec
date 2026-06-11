package io.kotest.extensions.wirespec.dsl

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.int

class InputTest : FunSpec({

    val rs = RandomSource.seeded(42L)

    test("Literal resolves to its captured value") {
        Input.Literal("hello").resolve(rs) shouldBe "hello"
    }

    test("FromArb pulls a sample from the underlying Arb") {
        val arb = io.kotest.property.Arb.int(1..1)
        Input.FromArb(arb).resolve(rs) shouldBe 1
    }

    test("Lazy resolves by invoking the builder each time") {
        var i = 0
        val lazy = Input.Lazy { "v${++i}" }
        lazy.resolve(rs) shouldBe "v1"
        lazy.resolve(rs) shouldBe "v2"
    }
})
