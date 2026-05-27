package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.emit.PackageName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CatalogFileEmitterTest : FunSpec({

    val pkg = PackageName("com.example.api")

    test("WirespecCatalog exposes one accessor per endpoint then channel under ScenarioBuilder.wirespec") {
        val emitted = CatalogFileEmitter.emit(
            endpointNames = listOf("PetCreate", "PetGet"),
            channelNames = listOf("PetCreatedChannel"),
            packageName = pkg,
        )

        emitted.file shouldBe "com/example/api/kotest/WirespecCatalog.kt"
        emitted.result shouldBe readGolden("WirespecCatalog.kt")
    }
})

private fun readGolden(name: String): String =
    CatalogFileEmitterTest::class.java.classLoader.getResource("golden/$name")!!.readText()
