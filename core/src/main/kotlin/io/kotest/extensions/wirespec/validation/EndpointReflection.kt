package io.kotest.extensions.wirespec.validation

import community.flock.wirespec.kotlin.Wirespec
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

@PublishedApi
internal class EndpointReflection private constructor(
    val endpointName: String,
    val pathClass: Class<*>,
    val queriesClass: Class<*>,
    val headersClass: Class<*>,
    val responseVariantsByStatus: Map<Int, Class<*>>,
    private val fromResponseMethod: Method,
    private val instance: Any,
    val requestConstructor: Constructor<*>,
    val requestConstructorParamNames: List<String>,
    val pathFieldNames: List<String>,
    val queriesFieldNames: List<String>,
    val headersFieldNames: List<String>,
    val hasBody: Boolean,
    val bodyElementClass: Class<*>?,
) {

    fun responseClassForStatus(status: Int): Class<*>? = responseVariantsByStatus[status]

    fun fromRawResponse(serialization: Wirespec.Serialization, response: Wirespec.RawResponse): Any =
        fromResponseMethod.invoke(instance, serialization, response)

    fun buildRequest(args: Map<String, Any?>): Any {
        val ordered = requestConstructorParamNames.map { name ->
            if (!args.containsKey(name)) {
                error(
                    "$endpointName.Request constructor parameter `$name` was not supplied. " +
                        "Set it on the EndpointCallBuilder via .path/.body/.query/.header.",
                )
            }
            args[name]
        }
        return try {
            requestConstructor.newInstance(*ordered.toTypedArray())
        } catch (t: Throwable) {
            error("Failed to build $endpointName.Request with args=$args: ${t.cause?.message ?: t.message}")
        }
    }

    companion object {
        private val cache = ConcurrentHashMap<KClass<out Wirespec.Endpoint>, EndpointReflection>()

        fun of(endpoint: Wirespec.Endpoint): EndpointReflection {
            val cls = endpoint::class
            cache[cls]?.let { return it }
            return introspect(endpoint, cls).also { cache[cls] = it }
        }

        private fun introspect(
            instance: Wirespec.Endpoint,
            cls: KClass<out Wirespec.Endpoint>,
        ): EndpointReflection {
            val jcls = cls.java
            val nested = jcls.declaredClasses.associateBy { it.simpleName }

            val requestClass = nested["Request"]
                ?: error("${cls.simpleName}: no nested Request type found.")
            val pathClass = nested["Path"]
                ?: error("${cls.simpleName}: no nested Path type found.")
            val queriesClass = nested["Queries"]
                ?: error("${cls.simpleName}: no nested Queries type found.")
            val headersClass = nested["Headers"]
                ?: nested["RequestHeaders"]
                ?: error("${cls.simpleName}: no nested Headers/RequestHeaders type found.")

            val variantRegex = Regex("Response(\\d{3})")
            val variants: Map<Int, Class<*>> = jcls.declaredClasses
                .mapNotNull { c ->
                    val name = c.simpleName ?: return@mapNotNull null
                    val match = variantRegex.matchEntire(name) ?: return@mapNotNull null
                    match.groupValues[1].toInt() to c
                }
                .toMap()
            require(variants.isNotEmpty()) {
                "${cls.simpleName}: no concrete ResponseNNN variants found."
            }

            val fromResponseMethod = jcls.declaredMethods.firstOrNull { it.name == "fromResponse" || it.name == "fromRawResponse" }
                ?: error("${cls.simpleName}: no fromResponse/fromRawResponse method found.")

            // Pick the user-facing secondary Request constructor: the emitter's
            // secondary flattens path/query/header fields and never declares the
            // primary's `method` parameter. Fewest-params alone is not enough —
            // an endpoint with many flattened params (e.g. 1 path + 6 queries)
            // has a secondary that is LARGER than the 5-arg primary. Fall back
            // to fewest-params for hand-rolled Request classes without retained
            // parameter names.
            val requestConstructor = requestClass.declaredConstructors
                .filter { ctor -> ctor.parameters.none { it.name == "method" } }
                .minByOrNull { it.parameterCount }
                ?: requestClass.declaredConstructors.minByOrNull { it.parameterCount }
                ?: error("${cls.simpleName}.Request: no constructors found.")
            val paramNames = requestConstructor.parameters.map { it.name }
            require(paramNames.all { it != null && !it.matches(Regex("arg\\d+")) }) {
                "${cls.simpleName}.Request constructor parameter names not retained. " +
                    "Ensure the generated module is compiled with `-java-parameters`."
            }

            val pathFieldNames = pathClass.declaredFields.map { it.name }
            val queriesFieldNames = queriesClass.declaredFields.map { it.name }
            val headersFieldNames = headersClass.declaredFields.map { it.name }
            val hasBody = "body" in paramNames

            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            val bodyElementClass: Class<*>? = if (hasBody) {
                val bodyParam = requestConstructor.parameters.first { it.name == "body" }
                val erased = bodyParam.type
                if (java.util.List::class.java.isAssignableFrom(erased)) {
                    val parameterized = bodyParam.parameterizedType as? java.lang.reflect.ParameterizedType
                    parameterized?.actualTypeArguments?.firstOrNull() as? Class<*>
                } else {
                    null
                }
            } else {
                null
            }

            return EndpointReflection(
                endpointName = cls.simpleName ?: cls.java.name,
                pathClass = pathClass,
                queriesClass = queriesClass,
                headersClass = headersClass,
                responseVariantsByStatus = variants,
                fromResponseMethod = fromResponseMethod,
                instance = instance,
                requestConstructor = requestConstructor,
                requestConstructorParamNames = paramNames.filterNotNull(),
                pathFieldNames = pathFieldNames,
                queriesFieldNames = queriesFieldNames,
                headersFieldNames = headersFieldNames,
                hasBody = hasBody,
                bodyElementClass = bodyElementClass,
            )
        }
    }
}
