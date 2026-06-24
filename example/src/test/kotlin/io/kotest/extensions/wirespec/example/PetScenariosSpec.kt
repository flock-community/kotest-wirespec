package io.kotest.extensions.wirespec.example

import community.flock.wirespec.generated.endpoint.CreatePet
import community.flock.wirespec.generated.endpoint.CreatePetsBulk
import community.flock.wirespec.generated.endpoint.DeletePet
import community.flock.wirespec.generated.endpoint.GetPet1
import community.flock.wirespec.generated.endpoint.ListPets
import community.flock.wirespec.generated.endpoint.UpdatePet
import community.flock.wirespec.generated.kotest.call
import community.flock.wirespec.integration.kotest.WirespecExtension
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Endpoint scenarios driven by the generated `<Endpoint>.call { … }` DSL. Each call builds
 * its request body/path/query with kotest `Arb`s (unset fields are generated from the
 * contract), sends it over real HTTP to the running app, and validates the typed response
 * variant against the Wirespec contract.
 *
 * The transport is supplied by the shared `PetTestEnvironment` via `ScenarioContextProvider`
 * (discovered through `META-INF/services`); this spec only mounts the ambient with
 * `@ApplyExtension`.
 */
@ApplyExtension(WirespecExtension::class)
class PetScenariosSpec : FunSpec({

    test("pet CRUD") {
        val petId = CreatePet.call {
            body = {
                name = Arb.constant("rex")
                species = Arb.constant("dog")
            }
            expecting<CreatePet.Response201>()
        }.body.id

        GetPet1.call { path = { id = Arb.constant(petId) }; expecting<GetPet1.Response200>() }

        UpdatePet.call {
            path = { id = Arb.constant(petId) }
            body = { name = Arb.constant("new name") }
            expecting<UpdatePet.Response200> { it.body.name shouldNotBe null }
        }

        GetPet1.call {
            path = { id = Arb.constant(petId) }
            expecting<GetPet1.Response200> { it.body.name shouldBe "new name" }
        }

        DeletePet.call { path = { id = Arb.constant(petId) }; expecting<DeletePet.Response204>() }
        GetPet1.call { path = { id = Arb.constant(petId) }; expecting<GetPet1.Response404>() }
    }

    test("getPet1 on a generated, non-existent id 404s") {
        GetPet1.call {
            path = { id = Arb.string(minSize = 1, maxSize = 24) }
            expecting<GetPet1.Response404>()
        }
    }

    test("typesafe queries") {
        checkAll<Int>(iterations = 8) {
            repeat(25) {
                CreatePet.call {
                    body = { name = Arb.constant("rex"); species = Arb.constant("dog") }
                    expecting<CreatePet.Response201>()
                }
            }
            ListPets.call {
                query = { limit = Arb.constant(10); offset = Arb.constant(0) }
                expecting<ListPets.Response200> { resp ->
                    resp.body.content.size shouldBe resp.body.total.coerceAtMost(10)
                }
            }
        }
    }

    test("createPetsBulk — bodyCount = 2..2 sends a 2-element list; response total=2") {
        CreatePetsBulk.call {
            bodyCount = 2..2
            body = { name = Arb.constant("rex"); species = Arb.constant("dog") }
            expecting<CreatePetsBulk.Response201> { response -> response.body.total shouldBe 2 }
        }
    }

    test("createPetsBulk — default bodyCount (1..3) generates between 1 and 3 elements") {
        checkAll<Int>(iterations = 5) {
            CreatePetsBulk.call {
                body = { name = Arb.constant("polly"); species = Arb.constant("parrot") }
                expecting<CreatePetsBulk.Response201> { response ->
                    response.body.total shouldBeGreaterThanOrEqual 1
                    response.body.total shouldBeLessThanOrEqual 3
                }
            }
        }
    }
})
