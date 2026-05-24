package io.kotest.extensions.spring.wirespec.validation

import community.flock.wirespec.kotlin.Wirespec
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.starProjectedType

/**
 * Minimal reflection over a generated `Wirespec.Channel` class. The generated
 * channel is just `fun interface <Name>Channel { operator fun invoke(message: T) }`
 * (see KotlinChannelDefinitionEmitter), so the only thing we need to recover at
 * runtime is the payload type.
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
            val invoke = cls.java.declaredMethods.firstOrNull { it.name == "invoke" }
                ?: error("${cls.simpleName}: no `invoke(message: …)` method found. " +
                    "Is this a Wirespec-generated channel?")
            val param = invoke.parameters.firstOrNull()
                ?: error("${cls.simpleName}.invoke has no parameters. Unexpected channel shape.")
            // Best-effort KType recovery from java.lang.reflect.Type. For
            // non-generic payloads this is exact; for generic payloads
            // (List<X>) we star-project — the Wirespec.Serialization
            // round-trip tolerates erased generic args at the JSON layer.
            val payloadKType = param.parameterizedType.let { t ->
                val rawClass = when (t) {
                    is Class<*> -> t.kotlin
                    is java.lang.reflect.ParameterizedType -> (t.rawType as Class<*>).kotlin
                    else -> error("${cls.simpleName}: unexpected parameter type $t")
                }
                rawClass.starProjectedType
            }
            return ChannelReflection(
                channelName = cls.simpleName ?: cls.java.name,
                payloadType = payloadKType,
            )
        }
    }
}
