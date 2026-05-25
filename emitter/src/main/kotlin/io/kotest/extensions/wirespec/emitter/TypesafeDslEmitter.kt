package io.kotest.extensions.wirespec.emitter

import arrow.core.NonEmptyList
import community.flock.wirespec.compiler.core.emit.EmitShared
import community.flock.wirespec.compiler.core.emit.Emitted
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.AST
import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import community.flock.wirespec.compiler.core.parse.ast.Type
import community.flock.wirespec.compiler.utils.Logger
import community.flock.wirespec.emitters.kotlin.KotlinIrEmitter

open class TypesafeDslEmitter(
    packageName: PackageName,
    emitShared: EmitShared,
) : KotlinIrEmitter(packageName, emitShared) {

    override fun emit(ast: AST, logger: Logger): NonEmptyList<Emitted> {
        val base = super.emit(ast, logger)
        val statements = ast.modules.toList().flatMap { it.statements.toList() }
        val types = statements.filterIsInstance<Type>().associateBy { it.identifier.value }

        val endpointDsl: List<Emitted> = statements
            .filterIsInstance<Endpoint>()
            .map { DslFileEmitter.emit(it, packageName, types) }
        val channelDsl: List<Emitted> = statements
            .filterIsInstance<Channel>()
            .map { ChannelDslFileEmitter.emit(it, packageName, types) }

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

        val extra = endpointDsl + channelDsl
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
}
