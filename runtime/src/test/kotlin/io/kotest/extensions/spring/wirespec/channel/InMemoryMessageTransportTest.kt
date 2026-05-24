package io.kotest.extensions.spring.wirespec.channel

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class InMemoryMessageTransportTest : FunSpec({

    test("publish + receive — receive sees previously published records on the same topic") {
        val transport = InMemoryMessageTransport()
        runBlocking {
            transport.publish(OutgoingRecord("events", key = null, body = "one".toByteArray()))
            transport.publish(OutgoingRecord("events", key = null, body = "two".toByteArray()))

            val received = transport.receive("events", atLeast = 2, within = 1.seconds)

            received shouldHaveSize 2
            received.map { String(it.body) } shouldBe listOf("one", "two")
        }
    }

    test("receive — filters by topic") {
        val transport = InMemoryMessageTransport()
        runBlocking {
            transport.publish(OutgoingRecord("a", null, "hit".toByteArray()))
            transport.publish(OutgoingRecord("b", null, "miss".toByteArray()))

            val received = transport.receive("a", atLeast = 1, within = 1.seconds)

            received.map { String(it.body) } shouldBe listOf("hit")
        }
    }

    test("receive — returns early once atLeast records are seen") {
        val transport = InMemoryMessageTransport()
        runBlocking {
            transport.publish(OutgoingRecord("events", null, "one".toByteArray()))
            transport.publish(OutgoingRecord("events", null, "two".toByteArray()))

            val elapsed = measureTime {
                transport.receive("events", atLeast = 2, within = 10.seconds)
            }

            (elapsed < 1.seconds) shouldBe true
        }
    }

    test("receive — times out when fewer than atLeast records arrive") {
        val transport = InMemoryMessageTransport()
        runBlocking {
            transport.publish(OutgoingRecord("events", null, "only-one".toByteArray()))

            val elapsed = measureTime {
                val records = transport.receive("events", atLeast = 5, within = 200.milliseconds)
                records shouldHaveSize 1
            }
            (elapsed >= 200.milliseconds) shouldBe true
        }
    }
})
