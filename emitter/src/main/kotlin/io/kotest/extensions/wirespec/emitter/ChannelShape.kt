package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Type

data class ChannelShape(
    val name: String,
    val payloadType: String,
    val payloadFields: List<EndpointShape.NamedTypedField>,
    val modelImports: List<String>,
) {
    val dslName: String get() = name.replaceFirstChar(Char::lowercaseChar)

    companion object {
        fun from(channel: Channel, types: Map<String, Type> = emptyMap()): ChannelShape {
            val payloadRef = channel.reference
            val payloadType = KotlinTypeMapper.map(payloadRef)
            val payloadFields = (payloadRef as? Reference.Custom)
                ?.let { types[it.value] }
                ?.shape?.value
                ?.map { EndpointShape.NamedTypedField(it.identifier.value, KotlinTypeMapper.map(it.reference)) }
                ?: emptyList()

            val modelImports = collectCustomNames(payloadRef).distinct()

            return ChannelShape(
                name = channel.identifier.value,
                payloadType = payloadType,
                payloadFields = payloadFields,
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
