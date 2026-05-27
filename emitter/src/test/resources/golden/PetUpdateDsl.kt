package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.ResultRef
import io.kotest.extensions.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import io.kotest.extensions.wirespec.dsl.EndpointCallBuilder.StreamingMode
import kotlin.time.Duration
import com.example.api.endpoint.PetUpdate
import io.kotest.property.Arb
import com.example.api.model.UpdatePetRequest
@WirespecScenarioDsl
public class PetUpdateCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.endpoint(PetUpdate.Handler, PetUpdate)
    public fun body(value: UpdatePetRequest): PetUpdateCall =
        apply { inner.body(value) }
    public fun body(arb: Arb<UpdatePetRequest>): PetUpdateCall =
        apply { inner.body(arb) }
    public fun path(id: String): PetUpdateCall =
        apply { inner.path(PetUpdate.Path(id = id)) }
    public fun path(id: ResultRef<String>): PetUpdateCall =
        apply { inner.path { PetUpdate.Path(id = id.require()) } }
    public fun path(builder: () -> PetUpdate.Path): PetUpdateCall =
        apply { inner.path(builder) }
    public inline fun <reified R : PetUpdate.Response<*>> expecting(): PetUpdateCall =
        apply { inner.expecting<R>() }
    public inline fun <reified R : PetUpdate.Response<*>> expecting(noinline block: (R) -> Unit): PetUpdateCall =
        apply { inner.expecting<R>(block) }
    public inline fun <reified R : PetUpdate.Response<*>, T> returning(noinline projection: (R) -> T): ResultRef<T> =
        inner.returning<R, T>(projection)
    public inline fun <reified R : PetUpdate.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit): PetUpdateCall =
        apply { inner.collecting<R>(count, block) }
    public inline fun <reified R : PetUpdate.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit): PetUpdateCall =
        apply { inner.collecting<R>(duration, block) }
}
