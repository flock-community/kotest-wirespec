package com.example.api.kotest
import io.kotest.extensions.spring.wirespec.dsl.ResultRef
import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.spring.wirespec.dsl.WirespecScenarioDsl
import io.kotest.extensions.spring.wirespec.dsl.EndpointCallBuilder.StreamingMode
import kotlin.time.Duration
import com.example.api.endpoint.PetList
public val ScenarioBuilder.petList: PetListCall
    get() = PetListCall(this)
@WirespecScenarioDsl
public class PetListCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.endpoint(PetList.Handler, PetList)
    public fun query(limit: Int, offset: Int): PetListCall =
        apply { inner.query(PetList.Queries(limit = limit, offset = offset)) }
    public fun query(builder: () -> PetList.Queries): PetListCall =
        apply { inner.query(builder) }
    public inline fun <reified R : PetList.Response<*>> expecting(): PetListCall =
        apply { inner.expecting<R>() }
    public inline fun <reified R : PetList.Response<*>> expecting(noinline block: (R) -> Unit): PetListCall =
        apply { inner.expecting<R>(block) }
    public inline fun <reified R : PetList.Response<*>, T> returning(noinline projection: (R) -> T): ResultRef<T> =
        inner.returning<R, T>(projection)
    public inline fun <reified R : PetList.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit): PetListCall =
        apply { inner.collecting<R>(count, block) }
    public inline fun <reified R : PetList.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit): PetListCall =
        apply { inner.collecting<R>(duration, block) }
}
