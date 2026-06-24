package example

import community.flock.wirespec.generated.endpoint.GetPet
import community.flock.wirespec.generated.kotest.call
import community.flock.wirespec.integration.kotest.WirespecExtension
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant
import io.kotest.property.checkAll

/**
 * Smoke test for the maven-plugin: proves the extracted-then-generated `<Endpoint>.call { }`
 * DSL drives a real HTTP call against the running app and validates the typed response. The
 * transport is supplied by [example.support.SmokeEnvironment] via
 * [example.support.SmokeContextProvider] (discovered through `META-INF/services`); the spec
 * only mounts the ambient.
 */
@ApplyExtension(WirespecExtension::class)
class PetSmokeSpec : FunSpec({

    test("getPet round-trips") {
        checkAll<Int>(iterations = 3) {
            GetPet.call {
                path = { id = Arb.constant("existing") }
                expecting<GetPet.Response200>()
            }
        }
    }
})
