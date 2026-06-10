package io.kotest.extensions.wirespec.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import io.kotest.extensions.wirespec.WirespecTestContext
import io.kotest.extensions.wirespec.spring.http
import io.kotest.extensions.wirespec.withWirespec
import io.kotest.extensions.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.wirespec.example.generated.endpoint.DeletePet
import io.kotest.extensions.wirespec.example.generated.endpoint.GetPet1
import io.kotest.extensions.wirespec.example.generated.endpoint.UpdatePet
import io.kotest.extensions.wirespec.example.generated.kotest.PetControllerV1
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

@SpringBootTest(
    classes = [ExampleApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class PetScenariosJUnitTest {

    @LocalServerPort
    var port: Int = 0

    private lateinit var ctx: WirespecTestContext

    @BeforeEach
    fun setUp() {
        ctx = WirespecTestContext.http(
            baseUrl = "http://localhost:$port",
            serialization = WirespecSerialization(jacksonObjectMapper()),
        )
    }

    @Test
    fun `pet CRUD`(): Unit = runBlocking {
        checkAll<Int>(iterations = 10) {
            withWirespec(ctx) {
                val petId = PetControllerV1.createPet
                    .returning<CreatePet.Response201, String> { it.body.id }

                PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response200>()

                val newName = Arb.string()
                val updated = PetControllerV1.updatePet
                    .path(petId)
                    .body { name = newName }
                    .expecting<UpdatePet.Response200> { it.body.name shouldNotBe null }

                PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response200> {
                    it.body.name shouldBe updated.body.name
                }
                PetControllerV1.deletePet.path(id = petId).expecting<DeletePet.Response204>()
                PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response404>()
            }
        }
    }
}
