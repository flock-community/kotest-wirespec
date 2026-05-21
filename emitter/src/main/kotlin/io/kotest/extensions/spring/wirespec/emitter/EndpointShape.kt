package io.kotest.extensions.spring.wirespec.emitter

import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import community.flock.wirespec.compiler.core.parse.ast.Reference

/**
 * Slot summary used by [DslFileEmitter] to decide which DSL methods to render.
 */
data class EndpointShape(
    val name: String,
    val pathFields: List<NamedTypedField>,
    val queryFields: List<NamedTypedField>,
    val headerFields: List<NamedTypedField>,
    val bodyType: String?,
) {
    /** `PetGet` -> `petGet`. */
    val dslName: String get() = name.replaceFirstChar(Char::lowercaseChar)

    data class NamedTypedField(val name: String, val kotlinType: String)

    companion object {
        fun from(endpoint: Endpoint): EndpointShape {
            val pathFields = endpoint.path
                .filterIsInstance<Endpoint.Segment.Param>()
                .map { NamedTypedField(it.identifier.value, KotlinTypeMapper.map(it.reference)) }
            val queryFields = endpoint.queries
                .map { NamedTypedField(it.identifier.value, KotlinTypeMapper.map(it.reference)) }
            val headerFields = endpoint.headers
                .map { NamedTypedField(it.identifier.value, KotlinTypeMapper.map(it.reference)) }
            val bodyType = endpoint.requests
                .firstOrNull()
                ?.content
                ?.reference
                ?.let { ref ->
                    when (ref) {
                        is Reference.Unit -> null
                        else -> KotlinTypeMapper.map(ref)
                    }
                }
            return EndpointShape(
                name = endpoint.identifier.value,
                pathFields = pathFields,
                queryFields = queryFields,
                headerFields = headerFields,
                bodyType = bodyType,
            )
        }
    }
}
