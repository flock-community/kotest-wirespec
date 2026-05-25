package io.kotest.extensions.spring.wirespec.validation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

data class PetCreatedPayload(val id: String, val name: String)

fun interface PetCreatedChannelStub : Wirespec.Channel {
    operator fun invoke(message: PetCreatedPayload)
}

class ChannelValidatorTest : FunSpec({

    val serialization: Wirespec.Serialization = WirespecSerialization(jacksonObjectMapper())
    val reflection = ChannelReflection.of(PetCreatedChannelStub::class)

    test("deserializes a well-formed payload") {
        val validator = ChannelValidator(reflection, serialization)
        val bytes = serialization.serializeBody(PetCreatedPayload("p-1", "Rex"), reflection.payloadType)

        val typed = validator.deserialize(bytes) as PetCreatedPayload

        typed.id shouldBe "p-1"
        typed.name shouldBe "Rex"
    }

    test("malformed payload — fails with channel-name + raw body in the message") {
        val validator = ChannelValidator(reflection, serialization)
        val malformed = "not a JSON object".toByteArray()

        val ex = runCatching { validator.deserialize(malformed) }.exceptionOrNull()
            ?: error("expected ChannelViolation")
        ex.message!!.let { msg ->
            msg shouldContain "PetCreatedChannelStub"
            msg shouldContain "not a JSON object"
        }
    }
})
