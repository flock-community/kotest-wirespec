package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.endpointCall
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import kotlin.time.Duration
import com.example.api.endpoint.NoSlots
@WirespecScenarioDsl
public class NoSlotsCall internal constructor() {
    @PublishedApi internal val inner = endpointCall(NoSlots.Handler, NoSlots)
    public suspend inline fun <reified R : NoSlots.Response<*>> expecting(): R =
        inner.expecting<R>()
    public suspend inline fun <reified R : NoSlots.Response<*>> expecting(noinline block: (R) -> Unit): R =
        inner.expecting<R>(block)
    public suspend inline fun <reified R : NoSlots.Response<*>, T> returning(noinline projection: (R) -> T): T =
        inner.returning<R, T>(projection)
    public suspend inline fun <reified R : NoSlots.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(count, block)
    }
    public suspend inline fun <reified R : NoSlots.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(duration, block)
    }
}
