package io.kotest.extensions.wirespec.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.v2.kotlin.WirespecSerialization
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.wirespec.WirespecChannelContext
import io.kotest.extensions.wirespec.WirespecTestContext
import io.kotest.extensions.wirespec.channel.InMemoryMessageTransport
import io.kotest.extensions.wirespec.dsl.ChannelCallBuilder
import io.kotest.extensions.wirespec.dsl.channelCall
import io.kotest.extensions.wirespec.withWirespec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.milliseconds

data class GreetingPayload(val text: String)

fun interface GreetingChannelStub : Wirespec.Channel {
    operator fun invoke(message: GreetingPayload)
}

class CallExecutorChannelTest : FunSpec({

    val serialization: Wirespec.Serialization = WirespecSerialization(jacksonObjectMapper())
    val noopHttp = object : Wirespec.Transportation {
        override suspend fun transport(request: Wirespec.RawRequest): Wirespec.RawResponse =
            error("no endpoint step in this test")
    }
    val httpCtx = WirespecTestContext(noopHttp, serialization)

    test("send then expecting on the same topic — round-trip via InMemory transport") {
        val channelCtx = WirespecChannelContext(InMemoryMessageTransport(), serialization)
        withWirespec(httpCtx, channelCtx, seed = 1L) {
            channelCall<GreetingPayload>(GreetingChannelStub::class).topic("greetings").send(GreetingPayload("hi"))
            val received = channelCall<GreetingPayload>(GreetingChannelStub::class).topic("greetings").expecting()
            received shouldBe GreetingPayload("hi")
        }
    }

    test("expecting times out and reports surplus/zero records") {
        val channelCtx = WirespecChannelContext(InMemoryMessageTransport(), serialization)
        val ex = runCatching {
            withWirespec(httpCtx, channelCtx, seed = 1L) {
                val call = channelCall<GreetingPayload>(GreetingChannelStub::class).topic("never-published")
                // Shorten the receive budget so the timeout path doesn't wait the default 2s.
                call.collectMode = ChannelCallBuilder.CollectMode.ByDuration(100.milliseconds)
                call.expecting()
            }
        }.exceptionOrNull() ?: error("expected timeout assertion error")
        ex.message!! shouldContain "expected exactly 1 message"
    }

    test("collecting(count) gathers exactly that many records") {
        val channelCtx = WirespecChannelContext(InMemoryMessageTransport(), serialization)
        withWirespec(httpCtx, channelCtx, seed = 1L) {
            channelCall<GreetingPayload>(GreetingChannelStub::class).topic("t").send(GreetingPayload("a"))
            channelCall<GreetingPayload>(GreetingChannelStub::class).topic("t").send(GreetingPayload("b"))
            channelCall<GreetingPayload>(GreetingChannelStub::class).topic("t").send(GreetingPayload("c"))
            val collected = channelCall<GreetingPayload>(GreetingChannelStub::class).topic("t")
                .collecting<GreetingPayload>(count = 3) { }
            collected shouldBe listOf(GreetingPayload("a"), GreetingPayload("b"), GreetingPayload("c"))
        }
    }

    test("a failing channel assertion surfaces the wirespec seed for reproduction") {
        val channelCtx = WirespecChannelContext(InMemoryMessageTransport(), serialization)
        val ex = runCatching {
            withWirespec(httpCtx, channelCtx, seed = 42L) {
                channelCall<GreetingPayload>(GreetingChannelStub::class).topic("g").send(GreetingPayload("hi"))
                channelCall<GreetingPayload>(GreetingChannelStub::class).topic("g")
                    .expecting<GreetingPayload> { it shouldBe GreetingPayload("WRONG") }
            }
        }.exceptionOrNull() ?: error("expected an assertion failure")
        ex.message!! shouldContain "wirespec seed=42"
    }
})
