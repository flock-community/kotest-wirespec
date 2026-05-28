package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Refined
import community.flock.wirespec.compiler.core.parse.ast.Type

data class EndpointShape(
    val name: String,
    val pathFields: List<NamedTypedField>,
    val queryFields: List<NamedTypedField>,
    val headerFields: List<NamedTypedField>,
    val bodyType: String?,
    val bodyFields: List<NamedTypedField>,
    val modelImports: List<String>,
) {
    val dslName: String get() = name.replaceFirstChar(Char::lowercaseChar)

    data class NamedTypedField(val name: String, val kotlinType: String)

    companion object {
        fun from(
            endpoint: Endpoint,
            types: Map<String, Type> = emptyMap(),
            refined: Map<String, Refined> = emptyMap(),
        ): EndpointShape {
            val pathFields = endpoint.path
                .filterIsInstance<Endpoint.Segment.Param>()
                .map { NamedTypedField(it.identifier.value, KotlinTypeMapper.map(it.reference)) }
            val queryFields = endpoint.queries
                .map { NamedTypedField(it.identifier.value, KotlinTypeMapper.map(it.reference)) }
            val headerFields = endpoint.headers
                .map { NamedTypedField(it.identifier.value, KotlinTypeMapper.map(it.reference)) }
            val bodyRef = endpoint.requests.firstOrNull()?.content?.reference
            val bodyType = bodyRef?.let { if (it is Reference.Unit) null else KotlinTypeMapper.map(it) }
            // Body-field kotlinType unwraps refined wrappers to their base primitive: the typed
            // body{} builder declares the field as Arb<BaseType>, and the runtime's RefinedWrapper
            // wraps each drawn primitive into the refined class via its single-arg ctor. Without
            // this unwrap, overriding a refined field at runtime throws "expected Arb<BaseType>
            // for refined …, got value of type …".
            val bodyFields = (bodyRef as? Reference.Custom)
                ?.let { types[it.value] }
                ?.shape?.value
                ?.map { NamedTypedField(it.identifier.value, mapWithRefinedUnwrap(it.reference, refined)) }
                ?: emptyList()

            val refs = buildList {
                endpoint.path.filterIsInstance<Endpoint.Segment.Param>().forEach { add(it.reference) }
                endpoint.queries.forEach { add(it.reference) }
                endpoint.headers.forEach { add(it.reference) }
                if (bodyRef != null) add(bodyRef)
            }
            val bodyFieldRefs = (bodyRef as? Reference.Custom)
                ?.let { types[it.value] }
                ?.shape?.value
                ?.map { it.reference }
                ?: emptyList()
            val modelImports = (refs + bodyFieldRefs).flatMap(::collectCustomNames).distinct()

            return EndpointShape(
                name = endpoint.identifier.value,
                pathFields = pathFields,
                queryFields = queryFields,
                headerFields = headerFields,
                bodyType = bodyType,
                bodyFields = bodyFields,
                modelImports = modelImports,
            )
        }

        private fun collectCustomNames(reference: Reference): List<String> = when (reference) {
            is Reference.Custom -> listOf(reference.value)
            is Reference.Iterable -> collectCustomNames(reference.reference)
            is Reference.Dict -> collectCustomNames(reference.reference)
            else -> emptyList()
        }

        /** Like [KotlinTypeMapper.map], but replaces a `Reference.Custom` to a [Refined] with the
         *  refined's underlying primitive type (preserving nullability/iterables/dicts wrapping). */
        internal fun mapWithRefinedUnwrap(reference: Reference, refined: Map<String, Refined>): String = when (reference) {
            is Reference.Custom -> refined[reference.value]?.let { r ->
                KotlinTypeMapper.map(r.reference.copy(isNullable = reference.isNullable))
            } ?: KotlinTypeMapper.map(reference)
            is Reference.Iterable -> {
                val inner = mapWithRefinedUnwrap(reference.reference, refined)
                if (reference.isNullable) "List<$inner>?" else "List<$inner>"
            }
            is Reference.Dict -> {
                val inner = mapWithRefinedUnwrap(reference.reference, refined)
                if (reference.isNullable) "Map<String, $inner>?" else "Map<String, $inner>"
            }
            else -> KotlinTypeMapper.map(reference)
        }
    }
}
