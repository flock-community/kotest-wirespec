package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.endpointCall
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import kotlin.time.Duration
import com.example.api.endpoint.PetGet
@WirespecScenarioDsl
public class PetGetCall internal constructor() {
    @PublishedApi internal val inner = endpointCall(PetGet.Handler, PetGet)
    public fun path(id: String): PetGetCall =
        apply { inner.path(PetGet.Path(id = id)) }
    public fun path(builder: () -> PetGet.Path): PetGetCall =
        apply { inner.path(builder) }
    public suspend inline fun <reified R : PetGet.Response<*>> expecting(): R =
        inner.expecting<R>()
    public suspend inline fun <reified R : PetGet.Response<*>> expecting(noinline block: (R) -> Unit): R =
        inner.expecting<R>(block)
    public suspend inline fun <reified R : PetGet.Response<*>, T> returning(noinline projection: (R) -> T): T =
        inner.returning<R, T>(projection)
    public suspend inline fun <reified R : PetGet.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(count, block)
    }
    public suspend inline fun <reified R : PetGet.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(duration, block)
    }
}
