package io.kotest.extensions.wirespec.dsl

import community.flock.wirespec.integration.kotest.KotestWirespecGeneratorBuilder
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.wirespec.runtime.CallExecutor
import io.kotest.extensions.wirespec.validation.EndpointReflection
import io.kotest.property.Arb
import kotlin.reflect.KClass
import kotlin.time.Duration

@WirespecScenarioDsl
class EndpointCallBuilder<BodyT : Any, Req : Wirespec.Request<BodyT>, Resp : Wirespec.Response<*>> internal constructor(
    @PublishedApi internal val client: Wirespec.Client<Req, Resp>,
    endpointObject: Wirespec.Endpoint,
) {

    @PublishedApi
    internal val reflection: EndpointReflection = EndpointReflection.of(endpointObject)

    @PublishedApi internal var pathInput: Input<Any>? = null
    internal var bodyInput: Input<Any>? = null
    @PublishedApi internal var queryInput: Input<Any>? = null
    @PublishedApi internal var headerInput: Input<Any>? = null

    internal var bodyOverrides: (KotestWirespecGeneratorBuilder.() -> Unit)? = null
    internal var bodyListSize: Arb<Int>? = null
    internal var expectedStatuses: Set<Int>? = null
    internal var customAssertion: ((Any) -> Unit)? = null

    fun body(value: BodyT): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        bodyInput = Input.Literal(value); bodyOverrides = null
    }

    fun body(arb: Arb<BodyT>): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        bodyInput = Input.FromArb(arb); bodyOverrides = null
    }

    fun body(overrides: KotestWirespecGeneratorBuilder.() -> Unit): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        bodyInput = null; bodyOverrides = overrides
    }

    fun bodyListSize(size: Arb<Int>): EndpointCallBuilder<BodyT, Req, Resp> = apply { bodyListSize = size }

    inline fun <reified P : Wirespec.Path> path(value: P): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        require(reflection.pathClass.isInstance(value)) {
            "${reflection.endpointName}.path: expected ${reflection.pathClass.simpleName}, got ${P::class.simpleName}"
        }
        pathInput = Input.Literal(value)
    }

    fun path(builder: () -> Wirespec.Path): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        pathInput = Input.Lazy { builder() as Any }
    }

    inline fun <reified Q : Wirespec.Queries> query(value: Q): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        require(reflection.queriesClass.isInstance(value)) {
            "${reflection.endpointName}.query: expected ${reflection.queriesClass.simpleName}, got ${Q::class.simpleName}"
        }
        queryInput = Input.Literal(value)
    }

    fun query(builder: () -> Wirespec.Queries): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        queryInput = Input.Lazy { builder() as Any }
    }

    inline fun <reified H : Wirespec.Request.Headers> header(value: H): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        require(reflection.headersClass.isInstance(value)) {
            "${reflection.endpointName}.header: expected ${reflection.headersClass.simpleName}, got ${H::class.simpleName}"
        }
        headerInput = Input.Literal(value)
    }

    fun header(builder: () -> Wirespec.Request.Headers): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        headerInput = Input.Lazy { builder() as Any }
    }

    // ---- terminals (eager, suspend) ----

    suspend inline fun <reified R : Resp> expecting(): R = expecting(R::class)

    suspend fun <R : Resp> expecting(variantClass: KClass<R>): R {
        expectedStatuses = setOf(statusOf(variantClass))
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeEndpoint(this) as R
    }

    suspend inline fun <reified R : Resp> expecting(noinline block: (R) -> Unit): R = expecting(R::class, block)

    suspend fun <R : Resp> expecting(variantClass: KClass<R>, block: (R) -> Unit): R {
        expectedStatuses = setOf(statusOf(variantClass))
        @Suppress("UNCHECKED_CAST")
        customAssertion = { response -> block(response as R) }
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeEndpoint(this) as R
    }

    suspend inline fun <reified R : Resp, T> returning(noinline projection: (R) -> T): T = returning(R::class, projection)

    suspend fun <R : Resp, T> returning(variantClass: KClass<R>, projection: (R) -> T): T {
        expectedStatuses = setOf(statusOf(variantClass))
        val resp = CallExecutor.executeEndpoint(this)
        @Suppress("UNCHECKED_CAST")
        return projection(resp as R)
    }

    suspend inline fun <reified R : Resp> collecting(count: Int, noinline block: (List<R>) -> Unit) =
        collecting(R::class, StreamingMode.ByCount(count), block)

    suspend inline fun <reified R : Resp> collecting(duration: Duration, noinline block: (List<R>) -> Unit) =
        collecting(R::class, StreamingMode.ByDuration(duration), block)

    /**
     * Endpoint streaming is not yet implemented: [mode]'s count/duration are not
     * honored — the single validated response is delivered as a one-element list.
     */
    suspend fun <R : Resp> collecting(variantClass: KClass<R>, mode: StreamingMode, block: (List<R>) -> Unit) {
        expectedStatuses = setOf(statusOf(variantClass))
        val resp = CallExecutor.executeEndpoint(this)
        @Suppress("UNCHECKED_CAST")
        block(listOf(resp as R))
    }

    @PublishedApi
    internal fun statusOf(variantClass: KClass<*>): Int {
        val name = variantClass.simpleName
            ?: error("Anonymous response variant class — pass a named ResponseNNN class.")
        val match = STATUS_REGEX.matchEntire(name)
            ?: error("Response variant class name '$name' doesn't match ResponseNNN. Use a Wirespec-generated response variant.")
        return match.groupValues[1].toInt()
    }

    sealed class StreamingMode {
        data class ByCount(val count: Int) : StreamingMode()
        data class ByDuration(val duration: Duration) : StreamingMode()
    }

    companion object {
        @PublishedApi
        internal val STATUS_REGEX = Regex("Response(\\d{3})")
    }
}

@DslMarker
annotation class WirespecScenarioDsl
