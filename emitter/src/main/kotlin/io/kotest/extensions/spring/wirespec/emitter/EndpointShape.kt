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

internal object KotlinTypeMapper {
    fun map(reference: Reference): String = when (reference) {
        is Reference.Primitive -> when (val t = reference.type) {
            is Reference.Primitive.Type.String -> "String"
            is Reference.Primitive.Type.Integer -> when (t.precision) {
                Reference.Primitive.Type.Precision.P32 -> "Int"
                Reference.Primitive.Type.Precision.P64 -> "Long"
            }
            is Reference.Primitive.Type.Number -> when (t.precision) {
                Reference.Primitive.Type.Precision.P32 -> "Float"
                Reference.Primitive.Type.Precision.P64 -> "Double"
            }
            Reference.Primitive.Type.Boolean -> "Boolean"
            Reference.Primitive.Type.Bytes -> "ByteArray"
        }.appendNullable(reference.isNullable)
        is Reference.Custom -> reference.value.appendNullable(reference.isNullable)
        is Reference.Iterable -> "List<${map(reference.reference)}>".appendNullable(reference.isNullable)
        is Reference.Dict -> "Map<String, ${map(reference.reference)}>".appendNullable(reference.isNullable)
        is Reference.Unit -> "Unit".appendNullable(reference.isNullable)
        is Reference.Any -> "Any".appendNullable(reference.isNullable)
    }

    private fun String.appendNullable(isNullable: Boolean): String = if (isNullable) "$this?" else this
}
