package io.kotest.extensions.wirespec.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import io.kotest.extensions.wirespec.WirespecTestContext
import io.kotest.extensions.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.wirespec.example.generated.endpoint.DeletePet
import io.kotest.extensions.wirespec.example.generated.endpoint.GetPet
import io.kotest.extensions.wirespec.example.generated.endpoint.ListPets
import io.kotest.extensions.wirespec.example.generated.endpoint.UpdatePet
import io.kotest.extensions.wirespec.example.generated.kotest.createPet
import io.kotest.extensions.wirespec.example.generated.kotest.deletePet
import io.kotest.extensions.wirespec.example.generated.kotest.getPet
import io.kotest.extensions.wirespec.example.generated.kotest.listPets
import io.kotest.extensions.wirespec.example.generated.kotest.updatePet
import io.kotest.extensions.wirespec.scenario
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

/**
 * JUnit Jupiter twin of [PetScenariosSpec]. Demonstrates that the `scenario(...)` DSL
 * is framework-neutral: the inner block is identical to the Kotest example;
 * only the outer wiring (`@SpringBootTest` + `@Test fun = runBlocking { checkAll { … } }`)
 * is JUnit-flavored.
 */
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
            scenario(ctx) {
                val petId = createPet
                    .returning<CreatePet.Response201, String> { it.body.id }

                getPet.path(petId).expecting<GetPet.Response200>()

                val newName = Arb.string()
                updatePet
                    .path(petId)
                    .body { name = newName }
                    .expecting<UpdatePet.Response200> { it.body.name shouldNotBe null }

                getPet.path(petId).expecting<GetPet.Response200>()
                deletePet.path(id = petId).expecting<DeletePet.Response204>()
                getPet.path(petId).expecting<GetPet.Response404>()
            }
        }
    }

    @Test
    fun `typesafe queries`(): Unit = runBlocking {
        checkAll<Int>(iterations = 8) {
            scenario(ctx) {
                repeat(25) { createPet.expecting<CreatePet.Response201>() }
                listPets
                    .query(limit = 10, offset = 0)
                    .expecting<ListPets.Response200> { resp ->
                        resp.body.content.size shouldBe resp.body.total.coerceAtMost(10)
                    }
            }
        }
    }
}
