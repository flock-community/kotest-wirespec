package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.endpointCall
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import kotlin.time.Duration
import com.example.api.endpoint.PetCreateBulk
import io.kotest.property.Arb
import io.kotest.extensions.wirespec.dsl.asArb
import io.kotest.property.Gen
import io.kotest.property.arbitrary.int
import com.example.api.model.Pet
@WirespecScenarioDsl
public class PetCreateBulkCall internal constructor() {
    @PublishedApi internal val inner = endpointCall(PetCreateBulk.Handler, PetCreateBulk)
    public fun body(value: List<Pet>): PetCreateBulkCall =
        apply { inner.body(value) }
    public fun body(arb: Arb<List<Pet>>): PetCreateBulkCall =
        apply { inner.body(arb) }
    public fun body(count: IntRange = 1..3, block: PetCreateBulkPetBodyBuilder.() -> Unit): PetCreateBulkCall = apply {
        val builder = PetCreateBulkPetBodyBuilder().apply(block)
        inner.bodyListSize(Arb.int(count))
        inner.body {
            builder.name?.let { registerPath("*", "name") { it.asArb() } }
        }
    }
    public suspend inline fun <reified R : PetCreateBulk.Response<*>> expecting(): R =
        inner.expecting<R>()
    public suspend inline fun <reified R : PetCreateBulk.Response<*>> expecting(noinline block: (R) -> Unit): R =
        inner.expecting<R>(block)
    public suspend inline fun <reified R : PetCreateBulk.Response<*>, T> returning(noinline projection: (R) -> T): T =
        inner.returning<R, T>(projection)
    public suspend inline fun <reified R : PetCreateBulk.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(count, block)
    }
    public suspend inline fun <reified R : PetCreateBulk.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(duration, block)
    }
}
@WirespecScenarioDsl
public class PetCreateBulkPetBodyBuilder {
    public var name: Gen<String>? = null
}
