package io.kotest.extensions.spring.wirespec.emitter

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

        val extra = endpointDsl + channelDsl
        return if (extra.isEmpty()) base else NonEmptyList(base.head, base.tail + extra)
    }
}
