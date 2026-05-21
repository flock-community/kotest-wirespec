package com.example.api.kotest
import io.kotest.extensions.spring.wirespec.dsl.ResultRef
import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.spring.wirespec.dsl.WirespecScenarioDsl
import io.kotest.extensions.spring.wirespec.dsl.EndpointCallBuilder.StreamingMode
import kotlin.time.Duration
import com.example.api.endpoint.HeaderEndpoint
public val ScenarioBuilder.headerEndpoint: HeaderEndpointCall
    get() = HeaderEndpointCall(this)
@WirespecScenarioDsl
public class HeaderEndpointCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.endpoint(HeaderEndpoint.Handler, HeaderEndpoint)
    public fun header(auth: String): HeaderEndpointCall =
        apply { inner.header(HeaderEndpoint.RequestHeaders(auth = auth)) }
    public fun header(builder: () -> HeaderEndpoint.RequestHeaders): HeaderEndpointCall =
        apply { inner.header(builder) }
    public inline fun <reified R : HeaderEndpoint.Response<*>> expecting(): HeaderEndpointCall =
        apply { inner.expecting<R>() }
    public inline fun <reified R : HeaderEndpoint.Response<*>> expecting(noinline block: (R) -> Unit): HeaderEndpointCall =
        apply { inner.expecting<R>(block) }
    public inline fun <reified R : HeaderEndpoint.Response<*>, T> returning(noinline projection: (R) -> T): ResultRef<T> =
        inner.returning<R, T>(projection)
    public inline fun <reified R : HeaderEndpoint.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit): HeaderEndpointCall =
        apply { inner.collecting<R>(count, block) }
    public inline fun <reified R : HeaderEndpoint.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit): HeaderEndpointCall =
        apply { inner.collecting<R>(duration, block) }
}
