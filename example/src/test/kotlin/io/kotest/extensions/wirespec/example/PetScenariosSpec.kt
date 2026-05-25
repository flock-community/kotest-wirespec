package io.kotest.extensions.wirespec.example

import io.kotest.extensions.wirespec.SpringWirespecSpec
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.string
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [ExampleApplication::class])
@AutoConfigureMockMvc
class PetScenariosSpec : SpringWirespecSpec({

    test("pet CRUD", iterations = 10) {
        val petId = createPet
            .returning<CreatePet.Response201, String> { it.body.id }

        getPet
            .path(petId)
            .expecting<GetPet.Response200>()

        updatePet
            .path(petId)
            .body { name = Arb.constant("new name") }
            .expecting<UpdatePet.Response200> { it.body.name shouldNotBe null }

        getPet
            .path(petId)
            .expecting<GetPet.Response200>{
                it.body.name shouldBe "new name"
            }

        deletePet
            .path(id = petId)
            .expecting<DeletePet.Response204>()

        getPet
            .path(petId)
            .expecting<GetPet.Response404>()
    }

    test("typesafe queries", iterations = 8) {
        repeat(25) { createPet.expecting<CreatePet.Response201>() }
        listPets
            .query(limit = 10, offset = 0)
            .expecting<ListPets.Response200> { resp ->
                resp.body.content.size shouldBe resp.body.total.coerceAtMost(10)
            }
    }
})
