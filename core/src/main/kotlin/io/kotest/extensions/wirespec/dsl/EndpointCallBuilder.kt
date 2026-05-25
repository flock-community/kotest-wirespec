package io.kotest.extensions.wirespec.dsl

import community.flock.wirespec.integration.kotest.KotestWirespecGeneratorBuilder
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.wirespec.validation.EndpointReflection
import io.kotest.property.Arb
import kotlin.reflect.KClass
import kotlin.time.Duration

@WirespecScenarioDsl
class EndpointCallBuilder<BodyT : Any, Req : Wirespec.Request<BodyT>, Resp : Wirespec.Response<*>> internal constructor(
    private val scenario: ScenarioBuilder,
    internal val client: Wirespec.Client<Req, Resp>,
    endpointObject: Wirespec.Endpoint,
) {

    @PublishedApi
    internal val reflection: EndpointReflection = EndpointReflection.of(endpointObject)

    @PublishedApi internal var pathInput: Input<Any>? = null
    internal var bodyInput: Input<Any>? = null
    @PublishedApi internal var queryInput: Input<Any>? = null
    @PublishedApi internal var headerInput: Input<Any>? = null

    internal var bodyOverrides: (KotestWirespecGeneratorBuilder.() -> Unit)? = null

    internal var expectedStatuses: Set<Int>? = null

    internal var customAssertion: ((Any) -> Unit)? = null

    internal var streamingMode: StreamingMode? = null

    internal var returningProjection: ((Any) -> Any?)? = null
    internal var returnedRef: ResultRef<Any?>? = null

    init {
        scenario.register(this)
    }

    fun body(value: BodyT): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        bodyInput = Input.Literal(value)
        bodyOverrides = null
    }

    fun body(arb: Arb<BodyT>): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        bodyInput = Input.FromArb(arb)
        bodyOverrides = null
    }

    fun body(overrides: KotestWirespecGeneratorBuilder.() -> Unit): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        bodyInput = null
        bodyOverrides = overrides
    }

    inline fun <reified P : Wirespec.Path> path(value: P): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        require(reflection.pathClass.isInstance(value)) {
            "${reflection.endpointName}.path: expected ${reflection.pathClass.simpleName}, " +
                "got ${P::class.simpleName}"
        }
        pathInput = Input.Literal(value)
    }

    fun path(builder: () -> Wirespec.Path): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        pathInput = Input.Lazy { builder() as Any }
    }

    inline fun <reified Q : Wirespec.Queries> query(value: Q): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        require(reflection.queriesClass.isInstance(value)) {
            "${reflection.endpointName}.query: expected ${reflection.queriesClass.simpleName}, " +
                "got ${Q::class.simpleName}"
        }
        queryInput = Input.Literal(value)
    }

    fun query(builder: () -> Wirespec.Queries): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        queryInput = Input.Lazy { builder() as Any }
    }

    inline fun <reified H : Wirespec.Request.Headers> header(value: H): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        require(reflection.headersClass.isInstance(value)) {
            "${reflection.endpointName}.header: expected ${reflection.headersClass.simpleName}, " +
                "got ${H::class.simpleName}"
        }
        headerInput = Input.Literal(value)
    }

    fun header(builder: () -> Wirespec.Request.Headers): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        headerInput = Input.Lazy { builder() as Any }
    }

    inline fun <reified R : Resp> expecting(): EndpointCallBuilder<BodyT, Req, Resp> =
        expecting(R::class)

    fun <R : Resp> expecting(variantClass: KClass<R>): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        expectedStatuses = setOf(statusOf(variantClass))
    }

    inline fun <reified R : Resp> expecting(noinline block: (R) -> Unit): EndpointCallBuilder<BodyT, Req, Resp> =
        expecting(R::class, block)

    fun <R : Resp> expecting(variantClass: KClass<R>, block: (R) -> Unit): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        expectedStatuses = setOf(statusOf(variantClass))
        @Suppress("UNCHECKED_CAST")
        customAssertion = { response -> block(response as R) }
    }

    inline fun <reified R : Resp, T> returning(noinline projection: (R) -> T): ResultRef<T> =
        returning(R::class, projection)

    fun <R : Resp, T> returning(variantClass: KClass<R>, projection: (R) -> T): ResultRef<T> {
        expectedStatuses = setOf(statusOf(variantClass))
        val ref = ResultRef<T>(label = "${reflection.endpointName}.${variantClass.simpleName}")
        @Suppress("UNCHECKED_CAST")
        returnedRef = ref as ResultRef<Any?>
        returningProjection = { response ->
            @Suppress("UNCHECKED_CAST")
            projection(response as R)
        }
        return ref
    }

    inline fun <reified R : Resp> collecting(count: Int, noinline block: (List<R>) -> Unit): EndpointCallBuilder<BodyT, Req, Resp> =
        collecting(R::class, StreamingMode.ByCount(count), block)

    inline fun <reified R : Resp> collecting(duration: Duration, noinline block: (List<R>) -> Unit): EndpointCallBuilder<BodyT, Req, Resp> =
        collecting(R::class, StreamingMode.ByDuration(duration), block)

    fun <R : Resp> collecting(variantClass: KClass<R>, mode: StreamingMode, block: (List<R>) -> Unit): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        expectedStatuses = setOf(statusOf(variantClass))
        streamingMode = mode
        @Suppress("UNCHECKED_CAST")
        customAssertion = { events -> block(events as List<R>) }
    }

    private fun statusOf(variantClass: KClass<*>): Int {
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
        private val STATUS_REGEX = Regex("Response(\\d{3})")
    }
}

@DslMarker
annotation class WirespecScenarioDsl
