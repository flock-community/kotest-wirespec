package io.kotest.extensions.wirespec.emitter

import arrow.core.NonEmptyList
import community.flock.wirespec.compiler.core.emit.EmitShared
import community.flock.wirespec.compiler.core.emit.Emitted
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.AST
import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import community.flock.wirespec.compiler.core.parse.ast.Refined
import community.flock.wirespec.compiler.core.parse.ast.Type
import community.flock.wirespec.compiler.utils.Logger
import community.flock.wirespec.emitters.kotlin.KotlinIrEmitter

open class TypesafeDslEmitter(
    packageName: PackageName,
    emitShared: EmitShared,
) : KotlinIrEmitter(packageName, emitShared) {

    override fun emit(ast: AST, logger: Logger): NonEmptyList<Emitted> {
        val base = super.emit(ast, logger)
        val modules = ast.modules.toList()
        val allStatements = modules.flatMap { it.statements.toList() }
        val types = allStatements.filterIsInstance<Type>().associateBy { it.identifier.value }
        val refined = allStatements.filterIsInstance<Refined>().associateBy { it.identifier.value }

        val endpoints = allStatements.filterIsInstance<Endpoint>()
        val channels = allStatements.filterIsInstance<Channel>()

        val endpointDsl: List<Emitted> = endpoints.map { DslFileEmitter.emit(it, packageName, types, refined) }
        val channelDsl: List<Emitted> = channels.map { ChannelDslFileEmitter.emit(it, packageName, types, refined) }

        // One catalog object per source `.ws` module (controller), named after the
        // file. Modules with neither endpoints nor channels (e.g. types-only) emit
        // nothing.
        val catalog: List<Emitted> = modules.mapNotNull { module ->
            val moduleEndpoints = module.statements.toList().filterIsInstance<Endpoint>().map { it.identifier.value }
            val moduleChannels = module.statements.toList().filterIsInstance<Channel>().map { it.identifier.value }
            if (moduleEndpoints.isEmpty() && moduleChannels.isEmpty()) {
                null
            } else {
                CatalogFileEmitter.emit(
                    catalogName = catalogNameOf(module.fileUri.value),
                    endpointNames = moduleEndpoints,
                    channelNames = moduleChannels,
                    packageName = packageName,
                )
            }
        }

        // Upstream's KotlinIrEmitter only adds `import Wirespec` to a generated
        // file when its owning module's `needImports()` returns true. That check
        // misses channels: a `.ws` file containing only channels (+ their types)
        // emits without the import — even though the generated channel
        // interface extends `Wirespec.Channel` and emitted types implement
        // `Wirespec.Shape`. Patch the rendered text post-hoc.
        val fixedBase = NonEmptyList(
            head = fixWirespecImport(base.head),
            tail = base.tail.map(::fixWirespecImport),
        )

        val extra = endpointDsl + channelDsl + catalog
        return if (extra.isEmpty()) fixedBase else NonEmptyList(fixedBase.head, fixedBase.tail + extra)
    }

    private fun fixWirespecImport(emitted: Emitted): Emitted {
        val text = emitted.result
        if (!text.contains("Wirespec.") ||
            text.contains("import community.flock.wirespec.kotlin.Wirespec")
        ) {
            return emitted
        }
        val packageLine = Regex("""(?m)^package\s+\S+\s*$""").find(text) ?: return emitted
        val insertAt = packageLine.range.last + 1
        val patched = text.substring(0, insertAt) +
            "\nimport community.flock.wirespec.kotlin.Wirespec\nimport kotlin.reflect.typeOf" +
            text.substring(insertAt)
        return Emitted(file = emitted.file, result = patched)
    }

    /**
     * Derive the catalog object name from a module's `.ws` file URI basename.
     * Hand-authored basenames may contain characters that are illegal in a
     * Kotlin identifier (e.g. `tool-calls.ws`); camel-case across the illegal
     * separators so the catalog still compiles (`tool-calls` -> `toolCalls`).
     */
    private fun catalogNameOf(fileUri: String): String =
        fileUri.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".ws")
            .split(nonIdentifierChars)
            .filter { it.isNotEmpty() }
            .mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar(Char::uppercaseChar) }
            .joinToString("")
            .let { if (it.firstOrNull()?.isDigit() == true) "_$it" else it }

    private val nonIdentifierChars = Regex("[^A-Za-z0-9_]+")
}
