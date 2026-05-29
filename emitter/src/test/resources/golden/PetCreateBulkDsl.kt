package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.ResultRef
import io.kotest.extensions.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import io.kotest.extensions.wirespec.dsl.EndpointCallBuilder.StreamingMode
import kotlin.time.Duration
import com.example.api.endpoint.PetCreateBulk
import io.kotest.property.Arb
import com.example.api.model.Pet
@WirespecScenarioDsl
public class PetCreateBulkCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.endpoint(PetCreateBulk.Handler, PetCreateBulk)
    public fun body(value: List<Pet>): PetCreateBulkCall =
        apply { inner.body(value) }
    public fun body(arb: Arb<List<Pet>>): PetCreateBulkCall =
        apply { inner.body(arb) }
    public fun body(block: PetBodyBuilder.() -> Unit): PetCreateBulkCall = apply {
        val builder = PetBodyBuilder().apply(block)
        inner.body {
            builder.name?.let { registerPath("*", "name") { it } }
        }
    }
    public inline fun <reified R : PetCreateBulk.Response<*>> expecting(): PetCreateBulkCall =
        apply { inner.expecting<R>() }
    public inline fun <reified R : PetCreateBulk.Response<*>> expecting(noinline block: (R) -> Unit): PetCreateBulkCall =
        apply { inner.expecting<R>(block) }
    public inline fun <reified R : PetCreateBulk.Response<*>, T> returning(noinline projection: (R) -> T): ResultRef<T> =
        inner.returning<R, T>(projection)
    public inline fun <reified R : PetCreateBulk.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit): PetCreateBulkCall =
        apply { inner.collecting<R>(count, block) }
    public inline fun <reified R : PetCreateBulk.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit): PetCreateBulkCall =
        apply { inner.collecting<R>(duration, block) }
}
@WirespecScenarioDsl
public class PetBodyBuilder {
    public var name: Arb<String>? = null
}
