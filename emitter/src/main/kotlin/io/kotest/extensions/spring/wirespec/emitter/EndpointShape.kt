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
    /** Simple names of Custom (model) types referenced by any slot, in declaration order, de-duplicated. */
    val modelImports: List<String>,
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
            val bodyRef = endpoint.requests.firstOrNull()?.content?.reference
            val bodyType = bodyRef?.let { if (it is Reference.Unit) null else KotlinTypeMapper.map(it) }

            val refs = buildList {
                endpoint.path.filterIsInstance<Endpoint.Segment.Param>().forEach { add(it.reference) }
                endpoint.queries.forEach { add(it.reference) }
                endpoint.headers.forEach { add(it.reference) }
                if (bodyRef != null) add(bodyRef)
            }
            val modelImports = refs.flatMap(::collectCustomNames).distinct()

            return EndpointShape(
                name = endpoint.identifier.value,
                pathFields = pathFields,
                queryFields = queryFields,
                headerFields = headerFields,
                bodyType = bodyType,
                modelImports = modelImports,
            )
        }

        private fun collectCustomNames(reference: Reference): List<String> = when (reference) {
            is Reference.Custom -> listOf(reference.value)
            is Reference.Iterable -> collectCustomNames(reference.reference)
            is Reference.Dict -> collectCustomNames(reference.reference)
            else -> emptyList()
        }
    }
}
