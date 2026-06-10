package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.emit.PackageName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CatalogFileEmitterTest : FunSpec({

    val pkg = PackageName("com.example.api")

    test("emit produces a per-controller catalog object with one accessor per endpoint") {
        val emitted = CatalogFileEmitter.emit(
            catalogName = "PetControllerV1",
            endpointNames = listOf("PetCreate", "PetGet"),
            channelNames = emptyList(),
            packageName = pkg,
        )
        emitted.file shouldBe "com/example/api/kotest/PetControllerV1Catalog.kt"
        emitted.result.contains("public object PetControllerV1 {") shouldBe true
        emitted.result.contains("public val petCreate: PetCreateCall") shouldBe true
        emitted.result.contains("get() = PetCreateCall()") shouldBe true
        emitted.result.contains("ScenarioBuilder") shouldBe false
    }
})
