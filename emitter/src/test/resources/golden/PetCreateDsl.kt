package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.endpointCall
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import kotlin.time.Duration
import com.example.api.endpoint.PetCreate
import io.kotest.property.Arb
import com.example.api.model.CreatePetRequest
@WirespecScenarioDsl
public class PetCreateCall internal constructor() {
    @PublishedApi internal val inner = endpointCall(PetCreate.Handler, PetCreate)
    public fun body(value: CreatePetRequest): PetCreateCall =
        apply { inner.body(value) }
    public fun body(arb: Arb<CreatePetRequest>): PetCreateCall =
        apply { inner.body(arb) }
    public suspend inline fun <reified R : PetCreate.Response<*>> expecting(): R =
        inner.expecting<R>()
    public suspend inline fun <reified R : PetCreate.Response<*>> expecting(noinline block: (R) -> Unit): R =
        inner.expecting<R>(block)
    public suspend inline fun <reified R : PetCreate.Response<*>, T> returning(noinline projection: (R) -> T): T =
        inner.returning<R, T>(projection)
    public suspend inline fun <reified R : PetCreate.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(count, block)
    }
    public suspend inline fun <reified R : PetCreate.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(duration, block)
    }
}
