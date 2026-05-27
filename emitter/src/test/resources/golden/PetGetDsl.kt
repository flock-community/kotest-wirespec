package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.ResultRef
import io.kotest.extensions.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import io.kotest.extensions.wirespec.dsl.EndpointCallBuilder.StreamingMode
import kotlin.time.Duration
import com.example.api.endpoint.PetGet
@WirespecScenarioDsl
public class PetGetCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.endpoint(PetGet.Handler, PetGet)
    public fun path(id: String): PetGetCall =
        apply { inner.path(PetGet.Path(id = id)) }
    public fun path(id: ResultRef<String>): PetGetCall =
        apply { inner.path { PetGet.Path(id = id.require()) } }
    public fun path(builder: () -> PetGet.Path): PetGetCall =
        apply { inner.path(builder) }
    public inline fun <reified R : PetGet.Response<*>> expecting(): PetGetCall =
        apply { inner.expecting<R>() }
    public inline fun <reified R : PetGet.Response<*>> expecting(noinline block: (R) -> Unit): PetGetCall =
        apply { inner.expecting<R>(block) }
    public inline fun <reified R : PetGet.Response<*>, T> returning(noinline projection: (R) -> T): ResultRef<T> =
        inner.returning<R, T>(projection)
    public inline fun <reified R : PetGet.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit): PetGetCall =
        apply { inner.collecting<R>(count, block) }
    public inline fun <reified R : PetGet.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit): PetGetCall =
        apply { inner.collecting<R>(duration, block) }
}
