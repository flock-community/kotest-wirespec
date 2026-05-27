package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.emit.Emitted
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.ir.core.file
import community.flock.wirespec.ir.generator.KotlinGenerator

/**
 * Emits a single `WirespecCatalog.kt` aggregating every endpoint and channel
 * under one `ScenarioBuilder.wirespec` accessor. Qualified access
 * (`wirespec.createPet`) keeps IDE completion to just the contract's operations
 * — bare-prefix completion inside `scenario { }` would otherwise surface every
 * accessible top-level declaration (stdlib `createTempFile`, … ).
 *
 * The generated `*Call` classes live in the same `<pkg>.kotest` package, so the
 * catalog references them without imports and reaches their `internal`
 * constructors within the consumer's generated module.
 */
object CatalogFileEmitter {

    fun emit(
        endpointNames: List<String>,
        channelNames: List<String>,
        packageName: PackageName,
    ): Emitted {
        val kotestPkg = "${packageName.value}.kotest"
        val filePath = kotestPkg.replace('.', '/') + "/WirespecCatalog.kt"

        val irFile = file("WirespecCatalog") {
            `package`(kotestPkg)

            import("io.kotest.extensions.wirespec.dsl", "ScenarioBuilder")
            import("io.kotest.extensions.wirespec.dsl", "WirespecScenarioDsl")

            raw(renderExtensionProperty())
            raw(renderCatalogClass(endpointNames + channelNames))
        }

        return Emitted(file = filePath, result = KotlinGenerator.generate(irFile))
    }

    private fun renderExtensionProperty(): String =
        "public val ScenarioBuilder.wirespec: WirespecCatalog\n" +
            "    get() = WirespecCatalog(this)"

    private fun renderCatalogClass(names: List<String>): String = buildString {
        appendLine("@WirespecScenarioDsl")
        appendLine("public class WirespecCatalog internal constructor(private val scenario: ScenarioBuilder) {")
        names.forEach { name ->
            val dslName = name.replaceFirstChar(Char::lowercaseChar)
            appendLine("    public val $dslName: ${name}Call")
            appendLine("        get() = ${name}Call(scenario)")
        }
        append("}")
    }
}
