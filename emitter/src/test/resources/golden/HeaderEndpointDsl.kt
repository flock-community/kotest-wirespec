package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.endpointCall
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import kotlin.time.Duration
import com.example.api.endpoint.HeaderEndpoint
@WirespecScenarioDsl
public class HeaderEndpointCall internal constructor() {
    @PublishedApi internal val inner = endpointCall(HeaderEndpoint.Handler, HeaderEndpoint)
    public fun header(auth: String): HeaderEndpointCall =
        apply { inner.header(HeaderEndpoint.RequestHeaders(auth = auth)) }
    public fun header(builder: () -> HeaderEndpoint.RequestHeaders): HeaderEndpointCall =
        apply { inner.header(builder) }
    public suspend inline fun <reified R : HeaderEndpoint.Response<*>> expecting(): R =
        inner.expecting<R>()
    public suspend inline fun <reified R : HeaderEndpoint.Response<*>> expecting(noinline block: (R) -> Unit): R =
        inner.expecting<R>(block)
    public suspend inline fun <reified R : HeaderEndpoint.Response<*>, T> returning(noinline projection: (R) -> T): T =
        inner.returning<R, T>(projection)
    public suspend inline fun <reified R : HeaderEndpoint.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(count, block)
    }
    public suspend inline fun <reified R : HeaderEndpoint.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(duration, block)
    }
}
