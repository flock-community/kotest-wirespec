package com.example.api.kotest
import io.kotest.extensions.spring.wirespec.dsl.ResultRef
import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.spring.wirespec.dsl.WirespecScenarioDsl
import io.kotest.extensions.spring.wirespec.dsl.EndpointCallBuilder.StreamingMode
import kotlin.time.Duration
import com.example.api.endpoint.NoSlots
public val ScenarioBuilder.noSlots: NoSlotsCall
    get() = NoSlotsCall(this)
@WirespecScenarioDsl
public class NoSlotsCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.endpoint(NoSlots.Handler, NoSlots)
    public inline fun <reified R : NoSlots.Response<*>> expecting(): NoSlotsCall =
        apply { inner.expecting<R>() }
    public inline fun <reified R : NoSlots.Response<*>> expecting(noinline block: (R) -> Unit): NoSlotsCall =
        apply { inner.expecting<R>(block) }
    public inline fun <reified R : NoSlots.Response<*>, T> returning(noinline projection: (R) -> T): ResultRef<T> =
        inner.returning<R, T>(projection)
    public inline fun <reified R : NoSlots.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit): NoSlotsCall =
        apply { inner.collecting<R>(count, block) }
    public inline fun <reified R : NoSlots.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit): NoSlotsCall =
        apply { inner.collecting<R>(duration, block) }
}
