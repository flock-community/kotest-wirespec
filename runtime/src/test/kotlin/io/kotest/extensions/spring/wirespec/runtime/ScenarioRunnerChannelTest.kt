package io.kotest.extensions.spring.wirespec.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.wirespec.WirespecChannelContext
import io.kotest.extensions.spring.wirespec.WirespecTestContext
import io.kotest.extensions.spring.wirespec.channel.InMemoryMessageTransport
import io.kotest.extensions.spring.wirespec.scenario
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

data class GreetingPayload(val text: String)

fun interface GreetingChannelStub : Wirespec.Channel {
    operator fun invoke(message: GreetingPayload)
}

class ScenarioRunnerChannelTest : FunSpec({

    val serialization: Wirespec.Serialization = WirespecSerialization(jacksonObjectMapper())
    val noopHttp = object : Wirespec.Transportation {
        override suspend fun transport(request: Wirespec.RawRequest): Wirespec.RawResponse =
            error("no endpoint step in this test")
    }
    val httpCtx = WirespecTestContext(noopHttp, serialization)

    test("send then expecting on the same topic — round-trip via InMemory transport") {
        val transport = InMemoryMessageTransport()
        val channelCtx = WirespecChannelContext(transport, serialization)

        var received: GreetingPayload? = null
        scenario(httpCtx, channelCtx, seed = 1L) {
            channel<GreetingPayload>(GreetingChannelStub::class)
                .topic("greetings").send(GreetingPayload("hi"))
            channel<GreetingPayload>(GreetingChannelStub::class)
                .topic("greetings").expecting<GreetingPayload> { received = it }
        }

        received shouldBe GreetingPayload("hi")
    }

    test("expecting times out and reports surplus/zero records") {
        val transport = InMemoryMessageTransport()
        val channelCtx = WirespecChannelContext(transport, serialization)

        val ex = runCatching {
            scenario(httpCtx, channelCtx, seed = 1L) {
                channel<GreetingPayload>(GreetingChannelStub::class)
                    .topic("never-published").expecting<GreetingPayload> { }
            }
        }.exceptionOrNull() ?: error("expected timeout assertion error")

        ex.message!! shouldContain "expected exactly 1 message"
    }

    test("collecting(count) gathers exactly that many records") {
        val transport = InMemoryMessageTransport()
        val channelCtx = WirespecChannelContext(transport, serialization)

        var collected: List<GreetingPayload>? = null
        scenario(httpCtx, channelCtx, seed = 1L) {
            channel<GreetingPayload>(GreetingChannelStub::class).topic("t").send(GreetingPayload("a"))
            channel<GreetingPayload>(GreetingChannelStub::class).topic("t").send(GreetingPayload("b"))
            channel<GreetingPayload>(GreetingChannelStub::class).topic("t").send(GreetingPayload("c"))
            channel<GreetingPayload>(GreetingChannelStub::class).topic("t").collecting<GreetingPayload>(count = 3) {
                collected = it
            }
        }

        collected shouldBe listOf(GreetingPayload("a"), GreetingPayload("b"), GreetingPayload("c"))
    }
})
