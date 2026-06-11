package example

import example.generated.endpoint.GetPet
import example.generated.kotest.PetController
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringRootTestExtension
import io.kotest.extensions.wirespec.WirespecExtension
import io.kotest.property.checkAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest(classes = [ExampleApplication::class])
@AutoConfigureMockMvc
@ApplyExtension(SpringRootTestExtension::class, WirespecExtension::class)
class PetSmokeSpec : FunSpec({

    test("getPet round-trips") {
        checkAll<Int>(iterations = 3) {
            PetController.getPet
                .path("existing")
                .expecting<GetPet.Response200>()
        }
    }
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
