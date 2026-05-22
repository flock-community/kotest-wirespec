package io.kotest.extensions.spring.wirespec.example

import io.kotest.extensions.spring.wirespec.SpringScenarioSpec
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.DeletePet
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.GetPet
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.ListPets
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.UpdatePet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.createPet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.deletePet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.getPet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.listPets
import io.kotest.extensions.spring.wirespec.example.generated.kotest.updatePet
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string

class PetScenariosSpec : SpringScenarioSpec(ExampleApplication::class, {

    scenario("pet CRUD", iterations = 10) {
        val petId = createPet
            .returning<CreatePet.Response201, String> { it.body.id }

        getPet
            .path(petId)
            .expecting<GetPet.Response200>()

        val newName = Arb.string()
        updatePet
            .path(petId)
            .body {
                name = newName
            }
            .expecting<UpdatePet.Response200> { it.body.name shouldNotBe null }

        getPet
            .path(petId)
            .expecting<GetPet.Response200>()

        deletePet
            .path(id=petId)
            .expecting<DeletePet.Response204>()

        getPet
            .path(petId)
            .expecting<GetPet.Response404>()
    }

    scenario("typesafe queries", iterations = 8) {
        (1..25).forEach { _ ->
            createPet.expecting<CreatePet.Response201>()
        }
        listPets
            .query(limit = 10, offset = 0)
            .expecting<ListPets.Response200> { resp ->
                resp.body.content.size shouldBe resp.body.total.coerceAtMost(10)
            }
    }

})
