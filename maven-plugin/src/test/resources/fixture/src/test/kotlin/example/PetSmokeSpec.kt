package example

import example.generated.endpoint.GetPet
import example.generated.kotest.getPet
import io.kotest.extensions.wirespec.WirespecSpec
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest(classes = [ExampleApplication::class])
@AutoConfigureMockMvc
class PetSmokeSpec : WirespecSpec({

    test("getPet round-trips", iterations = 3) {
        getPet
            .path("existing")
            .expecting<GetPet.Response200>()
    }
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
