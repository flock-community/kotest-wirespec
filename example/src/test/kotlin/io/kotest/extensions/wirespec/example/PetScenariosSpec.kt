package io.kotest.extensions.wirespec.example

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringRootTestExtension
import io.kotest.extensions.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.wirespec.example.generated.endpoint.CreatePetsBulk
import io.kotest.extensions.wirespec.example.generated.endpoint.DeletePet
import io.kotest.extensions.wirespec.example.generated.endpoint.GetPet
import io.kotest.extensions.wirespec.example.generated.endpoint.ListPets
import io.kotest.extensions.wirespec.example.generated.endpoint.UpdatePet
import io.kotest.extensions.wirespec.example.generated.kotest.wirespec
import io.kotest.extensions.wirespec.scenario
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest(classes = [ExampleApplication::class])
@AutoConfigureMockMvc
@ApplyExtension(SpringRootTestExtension::class)
class PetScenariosSpec : FunSpec({

    test("pet CRUD") {
        scenario(iterations = 10) {
            val petId = wirespec.createPet
                .returning<CreatePet.Response201, String> { it.body.id }

            wirespec.getPet
                .path(petId)
                .expecting<GetPet.Response200>()

            wirespec.updatePet
                .path(petId)
                .body { name = Arb.constant("new name") }
                .expecting<UpdatePet.Response200> { it.body.name shouldNotBe null }

            wirespec.getPet
                .path(petId)
                .expecting<GetPet.Response200> {
                    it.body.name shouldBe "new name"
                }

            wirespec.deletePet
                .path(id = petId)
                .expecting<DeletePet.Response204>()

            wirespec.getPet
                .path(petId)
                .expecting<GetPet.Response404>()
        }
    }

    test("typesafe queries") {
        scenario(iterations = 8) {
            repeat(25) { wirespec.createPet.expecting<CreatePet.Response201>() }
            wirespec.listPets
                .query(limit = 10, offset = 0)
                .expecting<ListPets.Response200> { resp ->
                    resp.body.content.size shouldBe resp.body.total.coerceAtMost(10)
                }
        }
    }

    test("createPetsBulk — body(count = 2..2) { … } sends a 2-element list and the response shows total=2") {
        scenario(iterations = 1) {
            wirespec.createPetsBulk
                .body(count = 2..2) {
                    name = Arb.constant("rex")
                    species = Arb.constant("dog")
                }
                .expecting<CreatePetsBulk.Response201> { response ->
                    response.body.total shouldBe 2
                }
        }
    }

    test("createPetsBulk — default count (1..3) generates between 1 and 3 elements") {
        scenario(iterations = 5) {
            wirespec.createPetsBulk
                .body {
                    name = Arb.constant("polly")
                    species = Arb.constant("parrot")
                }
                .expecting<CreatePetsBulk.Response201> { response ->
                    response.body.total shouldBeGreaterThanOrEqual 1
                    response.body.total shouldBeLessThanOrEqual 3
                }
        }
    }
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
