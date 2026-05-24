package io.kotest.extensions.spring.wirespec.dsl

import community.flock.wirespec.kotlin.Wirespec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.RandomSource

// Minimal generated-channel stand-in: a `fun interface` with one invoke(message: T).
fun interface PetCreatedChannelStub : Wirespec.Channel {
    operator fun invoke(message: String)
}

class ChannelCallBuilderTest : FunSpec({

    val rs = RandomSource.seeded(0L)

    test("topic(value) sets a literal topic input") {
        val scenario = ScenarioBuilder(ArbReceiver(rs))
        val call = scenario.channel<String>(PetCreatedChannelStub::class)
        call.topic("pets.events")

        call.topicInput shouldBe Input.Literal("pets.events")
        scenario.steps.size shouldBe 1
    }

    test("topic(builder) sets a lazy topic input") {
        val call = ScenarioBuilder(ArbReceiver(rs)).channel<String>(PetCreatedChannelStub::class)
        call.topic { "dynamic.topic" }

        (call.topicInput as Input.Lazy).builder() shouldBe "dynamic.topic"
    }

    test("send(value) and expecting(block) on the same call — configuration error") {
        val call = ScenarioBuilder(ArbReceiver(rs)).channel<String>(PetCreatedChannelStub::class)
        call.topic("t").send("payload")

        val ex = runCatching { call.expecting<String> { } }.exceptionOrNull()
            ?: error("expected configuration error")
        ex.message!! shouldContain "cannot set both `send` and `expecting`"
    }

    test("expecting(block) then send(value) — same configuration error in the opposite order") {
        val call = ScenarioBuilder(ArbReceiver(rs)).channel<String>(PetCreatedChannelStub::class)
        call.topic("t").expecting<String> { }

        val ex = runCatching { call.send("payload") }.exceptionOrNull()
            ?: error("expected configuration error")
        ex.message!! shouldContain "cannot set both `send` and `expecting`"
    }

    test("collecting(count) sets the right receive policy") {
        val call = ScenarioBuilder(ArbReceiver(rs)).channel<String>(PetCreatedChannelStub::class)
        call.topic("t").collecting<String>(count = 3) { }

        val (atLeast, _) = call.receivePolicy()
        atLeast shouldBe 3
    }
})
