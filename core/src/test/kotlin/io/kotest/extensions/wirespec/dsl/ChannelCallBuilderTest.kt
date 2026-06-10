package io.kotest.extensions.wirespec.dsl

import community.flock.wirespec.kotlin.Wirespec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.RandomSource
import kotlin.time.Duration.Companion.seconds

// Minimal generated-channel stand-in: a `fun interface` with one invoke(message: T).
fun interface PetCreatedChannelStub : Wirespec.Channel {
    operator fun invoke(message: String)
}

class ChannelCallBuilderTest : FunSpec({

    test("topic(value) sets a literal topic input") {
        val call = channelCall<String>(PetCreatedChannelStub::class)
        call.topic("pets.events")
        call.topicInput shouldBe Input.Literal("pets.events")
    }

    test("topic(builder) sets a lazy topic input") {
        val call = channelCall<String>(PetCreatedChannelStub::class)
        call.topic { "dynamic.topic" }
        call.topicInput!!.resolve(RandomSource.seeded(0L)) shouldBe "dynamic.topic"
    }

    test("collecting mode drives the receive policy") {
        val call = channelCall<String>(PetCreatedChannelStub::class)
        call.collectMode = ChannelCallBuilder.CollectMode.ByCount(3)
        val (atLeast, within) = call.receivePolicy()
        atLeast shouldBe 3
        within shouldBe 3.seconds
    }
})
