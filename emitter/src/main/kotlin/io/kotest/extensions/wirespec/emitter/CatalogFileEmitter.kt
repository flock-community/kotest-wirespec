package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.emit.Emitted
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.ir.core.file
import community.flock.wirespec.ir.generator.KotlinGenerator

/**
 * Emits one top-level catalog `object` per source controller (`.ws` module),
 * named after the controller. `PetControllerV1.createPet…` reads as a bare
 * object access — no `scenario { }` receiver — and IDE completion stays scoped
 * to that controller's operations.
 *
 * The generated `*Call` classes live in the same `<pkg>.kotest` package, so the
 * object references them without imports and reaches their `internal`
 * constructors within the consumer's generated module.
 */
object CatalogFileEmitter {

    fun emit(
        catalogName: String,
        endpointNames: List<String>,
        channelNames: List<String>,
        packageName: PackageName,
    ): Emitted {
        val kotestPkg = "${packageName.value}.kotest"
        val filePath = kotestPkg.replace('.', '/') + "/${catalogName}Catalog.kt"

        val irFile = file("${catalogName}Catalog") {
            `package`(kotestPkg)
            raw(renderCatalogObject(catalogName, endpointNames + channelNames))
        }

        return Emitted(file = filePath, result = KotlinGenerator.generate(irFile))
    }

    private fun renderCatalogObject(catalogName: String, names: List<String>): String = buildString {
        appendLine("public object $catalogName {")
        names.forEach { name ->
            val dslName = name.replaceFirstChar(Char::lowercaseChar)
            appendLine("    public val $dslName: ${name}Call")
            appendLine("        get() = ${name}Call()")
        }
        append("}")
    }
}
