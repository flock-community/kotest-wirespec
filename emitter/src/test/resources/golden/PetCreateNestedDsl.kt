package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.endpointCall
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import kotlin.time.Duration
import com.example.api.endpoint.PetCreateNested
import io.kotest.property.Arb
import io.kotest.property.Gen
import com.example.api.model.Pet
import com.example.api.model.Owner
import com.example.api.model.Tag
@WirespecScenarioDsl
public class PetCreateNestedCall internal constructor() {
    @PublishedApi internal val inner = endpointCall(PetCreateNested.Handler, PetCreateNested)
    public fun body(value: Pet): PetCreateNestedCall =
        apply { inner.body(value) }
    public fun body(arb: Arb<Pet>): PetCreateNestedCall =
        apply { inner.body(arb) }
    public fun body(block: PetCreateNestedPetBodyBuilder.() -> Unit): PetCreateNestedCall = apply {
        val builder = PetCreateNestedPetBodyBuilder().apply(block)
        inner.body {
            builder.name?.let { registerPath("name") { it } }
            builder._ownerBlock?.let { block ->
                val nested_owner = PetCreateNestedOwnerBodyBuilder().apply(block)
                nested_owner.email?.let { registerPath("owner", "email") { it } }
            }
            builder._tagsBlock?.let { block ->
                val nested_tags = PetCreateNestedTagBodyBuilder().apply(block)
                nested_tags.label?.let { registerPath("tags", "*", "label") { it } }
            }
        }
    }
    public suspend inline fun <reified R : PetCreateNested.Response<*>> expecting(): R =
        inner.expecting<R>()
    public suspend inline fun <reified R : PetCreateNested.Response<*>> expecting(noinline block: (R) -> Unit): R =
        inner.expecting<R>(block)
    public suspend inline fun <reified R : PetCreateNested.Response<*>, T> returning(noinline projection: (R) -> T): T =
        inner.returning<R, T>(projection)
    public suspend inline fun <reified R : PetCreateNested.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(count, block)
    }
    public suspend inline fun <reified R : PetCreateNested.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(duration, block)
    }
}
@WirespecScenarioDsl
public class PetCreateNestedPetBodyBuilder {
    public var name: Gen<String>? = null
    @PublishedApi internal var _ownerBlock: (PetCreateNestedOwnerBodyBuilder.() -> Unit)? = null
    public fun owner(block: PetCreateNestedOwnerBodyBuilder.() -> Unit) { _ownerBlock = block }
    @PublishedApi internal var _tagsBlock: (PetCreateNestedTagBodyBuilder.() -> Unit)? = null
    public fun tags(block: PetCreateNestedTagBodyBuilder.() -> Unit) { _tagsBlock = block }
}
@WirespecScenarioDsl
public class PetCreateNestedOwnerBodyBuilder {
    public var email: Gen<String>? = null
}
@WirespecScenarioDsl
public class PetCreateNestedTagBodyBuilder {
    public var label: Gen<String>? = null
}
