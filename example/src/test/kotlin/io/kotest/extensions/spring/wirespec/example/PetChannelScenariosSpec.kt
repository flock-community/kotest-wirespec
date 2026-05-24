package io.kotest.extensions.spring.wirespec.example

import io.kotest.extensions.spring.wirespec.SpringWirespecSpec
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.GetPet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.createPet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.getPet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.onCreatePetCommand
import io.kotest.extensions.spring.wirespec.example.generated.kotest.publishPetCreated
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.test.context.EmbeddedKafka
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end channel-DSL spec against a real EmbeddedKafka broker. Covers
 * both directions:
 *
 *  - Producer: HTTP create causes the controller to publish a PetCreatedEvent
 *    on `pets.events`; the channel DSL's `.expecting { ... }` asserts on it.
 *  - Consumer: the test publishes a CreatePetCommand to `pets.commands`,
 *    waits a beat for the `@KafkaListener` to drain it, then verifies via
 *    HTTP GET that the pet appears in the repository with the correlation id.
 */
@SpringBootTest(classes = [ExampleApplication::class])
@AutoConfigureMockMvc
@EmbeddedKafka(topics = ["pets.events", "pets.commands"])
class PetChannelScenariosSpec : SpringWirespecSpec({

    test("HTTP create publishes a PetCreatedEvent", iterations = 1) {
        val petId = createPet
            .body { name = Arb.string(minSize = 1, maxSize = 16); species = Arb.string(minSize = 1, maxSize = 8) }
            .returning<CreatePet.Response201, String> { it.body.id }

        publishPetCreated
            .topic("pets.events")
            .expecting { it.id shouldBe petId.require() }
    }

    test("Kafka command creates a pet", iterations = 1) {
        val correlationId = onCreatePetCommand
            .topic("pets.commands")
            .send { name = Arb.string(minSize = 1, maxSize = 16) }
            .returning { it.correlationId }

        // Async @KafkaListener path — give it a moment to drain.
        delay(3.seconds)

        getPet
            .path(correlationId)
            .expecting<GetPet.Response200>()
    }
})
