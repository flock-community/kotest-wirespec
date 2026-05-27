package example

import example.generated.endpoint.GetPet
import example.generated.kotest.wirespec
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringRootTestExtension
import io.kotest.extensions.wirespec.scenario
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest(classes = [ExampleApplication::class])
@AutoConfigureMockMvc
@ApplyExtension(SpringRootTestExtension::class)
class PetSmokeSpec : FunSpec({

    test("getPet round-trips") {
        scenario(iterations = 3) {
            wirespec.getPet
                .path("existing")
                .expecting<GetPet.Response200>()
        }
    }
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
