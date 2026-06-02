package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.ResultRef
import io.kotest.extensions.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import io.kotest.extensions.wirespec.dsl.EndpointCallBuilder.StreamingMode
import kotlin.time.Duration
import com.example.api.endpoint.PetCreateNested
import io.kotest.property.Arb
import io.kotest.extensions.wirespec.dsl.asArb
import io.kotest.property.Gen
import com.example.api.model.Pet
import com.example.api.model.Owner
import com.example.api.model.Tag
@WirespecScenarioDsl
public class PetCreateNestedCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.endpoint(PetCreateNested.Handler, PetCreateNested)
    public fun body(value: Pet): PetCreateNestedCall =
        apply { inner.body(value) }
    public fun body(arb: Arb<Pet>): PetCreateNestedCall =
        apply { inner.body(arb) }
    public fun body(block: PetCreateNestedPetBodyBuilder.() -> Unit): PetCreateNestedCall = apply {
        val builder = PetCreateNestedPetBodyBuilder().apply(block)
        inner.body {
            builder.name?.let { registerPath("name") { it.asArb() } }
            builder._ownerBlock?.let { block ->
                val nested_owner = PetCreateNestedOwnerBodyBuilder().apply(block)
                nested_owner.email?.let { registerPath("owner", "email") { it.asArb() } }
            }
            builder._tagsBlock?.let { block ->
                val nested_tags = PetCreateNestedTagBodyBuilder().apply(block)
                nested_tags.label?.let { registerPath("tags", "*", "label") { it.asArb() } }
            }
        }
    }
    public inline fun <reified R : PetCreateNested.Response<*>> expecting(): PetCreateNestedCall =
        apply { inner.expecting<R>() }
    public inline fun <reified R : PetCreateNested.Response<*>> expecting(noinline block: (R) -> Unit): PetCreateNestedCall =
        apply { inner.expecting<R>(block) }
    public inline fun <reified R : PetCreateNested.Response<*>, T> returning(noinline projection: (R) -> T): ResultRef<T> =
        inner.returning<R, T>(projection)
    public inline fun <reified R : PetCreateNested.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit): PetCreateNestedCall =
        apply { inner.collecting<R>(count, block) }
    public inline fun <reified R : PetCreateNested.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit): PetCreateNestedCall =
        apply { inner.collecting<R>(duration, block) }
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
