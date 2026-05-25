package example

import example.generated.endpoint.GetPet
import example.generated.kotest.getPet
import io.kotest.extensions.spring.wirespec.SpringWirespecSpec
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [ExampleApplication::class])
@AutoConfigureMockMvc
class PetSmokeSpec : SpringWirespecSpec({

    test("getPet round-trips", iterations = 3) {
        getPet
            .path("existing")
            .expecting<GetPet.Response200>()
    }
})
