package example

import example.generated.endpoint.GetPet
import example.generated.kotest.getPet
import io.kotest.extensions.spring.wirespec.SpringScenarioSpec

class PetSmokeSpec : SpringScenarioSpec(ExampleApplication::class, {

    scenario("getPet round-trips", iterations = 3) {
        getPet
            .path("existing")
            .expecting<GetPet.Response200>()
    }
})
