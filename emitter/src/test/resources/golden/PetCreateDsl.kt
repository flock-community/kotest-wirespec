package com.example.api.kotest
import io.kotest.extensions.spring.wirespec.dsl.ResultRef
import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.spring.wirespec.dsl.WirespecScenarioDsl
import io.kotest.extensions.spring.wirespec.dsl.EndpointCallBuilder.StreamingMode
import kotlin.time.Duration
import com.example.api.endpoint.PetCreate
import io.kotest.property.Arb
import com.example.api.model.CreatePetRequest
public val ScenarioBuilder.petCreate: PetCreateCall
    get() = PetCreateCall(this)
@WirespecScenarioDsl
public class PetCreateCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.endpoint(PetCreate.Handler, PetCreate)
    public fun body(value: CreatePetRequest): PetCreateCall =
        apply { inner.body(value) }
    public fun body(arb: Arb<CreatePetRequest>): PetCreateCall =
        apply { inner.body(arb) }
    public inline fun <reified R : PetCreate.Response<*>> expecting(): PetCreateCall =
        apply { inner.expecting<R>() }
    public inline fun <reified R : PetCreate.Response<*>> expecting(noinline block: (R) -> Unit): PetCreateCall =
        apply { inner.expecting<R>(block) }
    public inline fun <reified R : PetCreate.Response<*>, T> returning(noinline projection: (R) -> T): ResultRef<T> =
        inner.returning<R, T>(projection)
    public inline fun <reified R : PetCreate.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit): PetCreateCall =
        apply { inner.collecting<R>(count, block) }
    public inline fun <reified R : PetCreate.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit): PetCreateCall =
        apply { inner.collecting<R>(duration, block) }
}
