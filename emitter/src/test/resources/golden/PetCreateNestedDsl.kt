package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.ResultRef
import io.kotest.extensions.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import io.kotest.extensions.wirespec.dsl.EndpointCallBuilder.StreamingMode
import kotlin.time.Duration
import com.example.api.endpoint.PetCreateNested
import io.kotest.property.Arb
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
    public fun body(block: PetBodyBuilder.() -> Unit): PetCreateNestedCall = apply {
        val builder = PetBodyBuilder().apply(block)
        inner.body {
            builder.name?.let { registerPath("name") { it } }
            builder._ownerBlock?.let { block ->
                val nested_owner = OwnerBodyBuilder().apply(block)
                nested_owner.email?.let { registerPath("owner", "email") { it } }
            }
            builder._tagsBlock?.let { block ->
                val nested_tags = TagBodyBuilder().apply(block)
                nested_tags.label?.let { registerPath("tags", "*", "label") { it } }
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
public class PetBodyBuilder {
    public var name: Arb<String>? = null
    @PublishedApi internal var _ownerBlock: (OwnerBodyBuilder.() -> Unit)? = null
    public fun owner(block: OwnerBodyBuilder.() -> Unit) { _ownerBlock = block }
    @PublishedApi internal var _tagsBlock: (TagBodyBuilder.() -> Unit)? = null
    public fun tags(block: TagBodyBuilder.() -> Unit) { _tagsBlock = block }
}
@WirespecScenarioDsl
public class OwnerBodyBuilder {
    public var email: Arb<String>? = null
}
@WirespecScenarioDsl
public class TagBodyBuilder {
    public var label: Arb<String>? = null
}
