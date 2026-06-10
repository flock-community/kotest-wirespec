package io.kotest.extensions.wirespec.example

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringRootTestExtension
import io.kotest.extensions.wirespec.WirespecExtension
import io.kotest.extensions.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.wirespec.example.generated.endpoint.GetPet1
import io.kotest.extensions.wirespec.example.generated.kotest.PetCommandListener
import io.kotest.extensions.wirespec.example.generated.kotest.PetControllerV1
import io.kotest.extensions.wirespec.example.generated.kotest.PetEventPublisher
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.kafka.test.context.EmbeddedKafka
import kotlin.time.Duration.Companion.seconds

@SpringBootTest(classes = [ExampleApplication::class])
@AutoConfigureMockMvc
@EmbeddedKafka(topics = ["pets.events", "pets.commands"])
@ApplyExtension(SpringRootTestExtension::class, WirespecExtension::class)
class PetChannelScenariosSpec : FunSpec({

    test("HTTP create publishes a PetCreatedEvent") {
        val petId = PetControllerV1.createPet
            .body {
                name = Arb.string(minSize = 1, maxSize = 16)
                species = Arb.string(minSize = 1, maxSize = 8)
            }
            .returning<CreatePet.Response201, String> { it.body.id }

        PetEventPublisher.publishPetCreated
            .topic("pets.events")
            .expecting { it.id shouldBe petId }
    }

    test("Kafka command creates a pet") {
        val correlationId = PetCommandListener.onCreatePetCommand
            .topic("pets.commands")
            .send()
            .correlationId

        eventually(5.seconds) {
            PetControllerV1.getPet1.path(correlationId).expecting<GetPet1.Response200>()
        }
    }
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
