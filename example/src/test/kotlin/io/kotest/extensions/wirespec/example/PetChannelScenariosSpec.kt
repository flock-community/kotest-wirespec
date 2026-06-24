package io.kotest.extensions.wirespec.example

import community.flock.wirespec.generated.channel.OnCreatePetCommand
import community.flock.wirespec.generated.channel.PublishPetCreated
import community.flock.wirespec.generated.endpoint.CreatePet
import community.flock.wirespec.generated.endpoint.GetPet1
import community.flock.wirespec.generated.kotest.call
import community.flock.wirespec.integration.kotest.WirespecExtension
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.wirespec.example.support.PetTestEnvironment
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.string
import kotlin.time.Duration.Companion.seconds

/**
 * Kafka channel scenarios driven by the generated `<Channel>.call { … }` DSL, against the
 * embedded broker from the shared `PetTestEnvironment`. Both directions are exercised: the
 * app reacting to an endpoint call by emitting an event the DSL then `expecting()`s, and the
 * DSL `send`ing a command the app's `@KafkaListener` consumes.
 *
 * `beforeEach` repositions the shared channel consumers at the log end so each scenario only
 * observes the events it produces.
 */
@ApplyExtension(WirespecExtension::class)
class PetChannelScenariosSpec : FunSpec({

    beforeEach { PetTestEnvironment.watchChannelsFromNow() }

    test("an HTTP create publishes a matching PetCreatedEvent") {
        val petId = CreatePet.call {
            body = {
                name = Arb.string(minSize = 1, maxSize = 16)
                species = Arb.string(minSize = 1, maxSize = 8)
            }
            expecting<CreatePet.Response201>()
        }.body.id

        PublishPetCreated.call {
            topic(PetTestEnvironment.EVENTS_TOPIC)
            expecting { it.id shouldBe petId }
        }
    }

    test("a command sent through the DSL is consumed by the application and creates a pet") {
        val command = OnCreatePetCommand.call {
            topic(PetTestEnvironment.COMMANDS_TOPIC)
            send {
                name = Arb.constant("rex")
                species = Arb.constant("dog")
            }
        }

        eventually(5.seconds) {
            GetPet1.call {
                path = { id = Arb.constant(command.correlationId) }
                expecting<GetPet1.Response200>()
            }
        }
    }
})
