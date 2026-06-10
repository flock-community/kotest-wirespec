package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.endpointCall
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import kotlin.time.Duration
import com.example.api.endpoint.PetList
@WirespecScenarioDsl
public class PetListCall internal constructor() {
    @PublishedApi internal val inner = endpointCall(PetList.Handler, PetList)
    public fun query(limit: Int, offset: Int): PetListCall =
        apply { inner.query(PetList.Queries(limit = limit, offset = offset)) }
    public fun query(builder: () -> PetList.Queries): PetListCall =
        apply { inner.query(builder) }
    public suspend inline fun <reified R : PetList.Response<*>> expecting(): R =
        inner.expecting<R>()
    public suspend inline fun <reified R : PetList.Response<*>> expecting(noinline block: (R) -> Unit): R =
        inner.expecting<R>(block)
    public suspend inline fun <reified R : PetList.Response<*>, T> returning(noinline projection: (R) -> T): T =
        inner.returning<R, T>(projection)
    public suspend inline fun <reified R : PetList.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(count, block)
    }
    public suspend inline fun <reified R : PetList.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(duration, block)
    }
}
