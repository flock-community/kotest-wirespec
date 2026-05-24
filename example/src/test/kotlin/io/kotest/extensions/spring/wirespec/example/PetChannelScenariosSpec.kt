package io.kotest.extensions.spring.wirespec.example

import io.kotest.extensions.spring.wirespec.SpringWirespecSpec
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.createPet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.publishPetCreated
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.test.context.EmbeddedKafka

/**
 * End-to-end producer-direction smoke for the channel DSL: an HTTP create
 * causes the controller to publish a PetCreatedEvent on `pets.events`, and
 * the channel DSL asserts on the published record via the
 * [io.kotest.extensions.spring.wirespec.channel.EmbeddedKafkaMessageTransport].
 *
 * Consumer-direction is intentionally not tested here. The current scenario
 * model collects steps and runs them in declaration order, so there is no
 * way to interleave "send command -> wait for async listener -> GET" inside
 * a single scenario; the unit-level `ScenarioRunnerChannelTest` already
 * exercises the consumer path against the InMemoryMessageTransport.
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
})
