package io.kotest.extensions.spring.wirespec.validation

import community.flock.wirespec.kotlin.Wirespec
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.declaredMemberFunctions

/**
 * Minimal reflection over a generated `Wirespec.Channel` class. The generated
 * channel is just `fun interface <Name>Channel { operator fun invoke(message: T) }`
 * (see KotlinChannelDefinitionEmitter), so the only thing we need to recover at
 * runtime is the payload type.
 *
 * Uses Kotlin reflection rather than `java.lang.reflect` so we get a proper
 * [KType] (not a `Class`-derived star projection) — the Wirespec Jackson
 * adapter's `deserializeBody(raw, kType)` needs the precise KType to construct
 * Jackson's `JavaType`.
 */
@PublishedApi
internal class ChannelReflection private constructor(
    val channelName: String,
    val payloadType: KType,
) {
    companion object {
        private val cache = ConcurrentHashMap<KClass<out Wirespec.Channel>, ChannelReflection>()

        fun of(channelClass: KClass<out Wirespec.Channel>): ChannelReflection =
            cache.computeIfAbsent(channelClass) { introspect(it) }

        private fun introspect(cls: KClass<out Wirespec.Channel>): ChannelReflection {
            val invoke = cls.declaredMemberFunctions.firstOrNull { it.name == "invoke" }
                ?: error("${cls.simpleName}: no `invoke(message: …)` function found. " +
                    "Is this a Wirespec-generated channel?")
            val messageParam = invoke.parameters.firstOrNull { it.kind == KParameter.Kind.VALUE }
                ?: error("${cls.simpleName}.invoke has no value parameter. Unexpected channel shape.")
            return ChannelReflection(
                channelName = cls.simpleName ?: cls.java.name,
                payloadType = messageParam.type,
            )
        }
    }
}
