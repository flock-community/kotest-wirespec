package com.example.api.endpoint

import io.kotest.extensions.spring.wirespec.dsl.ResultRef
import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.spring.wirespec.dsl.WirespecScenarioDsl
import io.kotest.extensions.spring.wirespec.dsl.EndpointCallBuilder.StreamingMode
import kotlin.time.Duration
import community.flock.wirespec.integration.kotest.KotestWirespecGeneratorBuilder
import io.kotest.property.Arb
import com.example.api.model.UpdatePetRequest

public fun ScenarioBuilder.petUpdate(block: PetUpdateCall.() -> Unit = {}): PetUpdateCall =
    PetUpdateCall(this).apply(block)

@WirespecScenarioDsl
public class PetUpdateCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.endpoint(PetUpdate.Handler)

    public fun body(value: UpdatePetRequest): PetUpdateCall =
        apply { inner.body(value) }

    public fun body(arb: Arb<UpdatePetRequest>): PetUpdateCall =
        apply { inner.body(arb) }

    public fun body(overrides: KotestWirespecGeneratorBuilder.() -> Unit): PetUpdateCall =
        apply { inner.body(overrides) }

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
