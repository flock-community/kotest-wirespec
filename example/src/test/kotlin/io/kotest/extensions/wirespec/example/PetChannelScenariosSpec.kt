package io.kotest.extensions.wirespec.example

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringRootTestExtension
import io.kotest.extensions.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.wirespec.example.generated.endpoint.GetPet
import io.kotest.extensions.wirespec.example.generated.kotest.wirespec
import io.kotest.extensions.wirespec.scenario
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
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
@ApplyExtension(SpringRootTestExtension::class)
class PetChannelScenariosSpec : FunSpec({

    test("HTTP create publishes a PetCreatedEvent") {
        scenario {
            val petId = wirespec.createPet
                .body { name = Arb.string(minSize = 1, maxSize = 16); species = Arb.string(minSize = 1, maxSize = 8) }
                .returning<CreatePet.Response201, String> { it.body.id }

            wirespec.publishPetCreated
                .topic("pets.events")
                .expecting { it.id shouldBe petId.require() }
        }
    }

    test("Kafka command creates a pet") {
        scenario(iterations = 10) {
            val correlationId = wirespec.onCreatePetCommand
                .topic("pets.commands")
                .send()
                .returning { it.correlationId }

            // Async @KafkaListener path — retry the HTTP assertion until the
            // listener has drained the command (or the timeout fires).
            eventually(timeout = 5.seconds) {
                wirespec.getPet
                    .path(correlationId)
                    .expecting<GetPet.Response200>()
            }
        }
    }
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
