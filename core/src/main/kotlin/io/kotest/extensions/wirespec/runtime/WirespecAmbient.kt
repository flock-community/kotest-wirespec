package io.kotest.extensions.wirespec.runtime

import io.kotest.core.spec.Spec
import io.kotest.extensions.wirespec.WirespecChannelContext
import io.kotest.extensions.wirespec.WirespecTestContext
import io.kotest.extensions.wirespec.context.ContextRegistry
import io.kotest.property.RandomSource
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Mutable holder for the per-test [RandomSource] so the property-run bridge
 * ([io.kotest.extensions.wirespec.useWirespecSeed]) can rebind it to a
 * `checkAll` iteration's source mid-test.
 */
class RandomSourceHolder(@Volatile var randomSource: RandomSource) {
    val seed: Long get() = randomSource.seed
}

/**
 * Coroutine-context element carrying everything an eager wirespec call needs.
 * The endpoint/channel context is resolved lazily on first use — either an
 * explicit override (JUnit / [withWirespec]) or via the [ContextRegistry] SPI
 * from the running [spec] (kotest / [WirespecExtension]). Lazy resolution avoids
 * any extension-ordering dependency with Spring's lifecycle extension.
 */
class WirespecAmbient internal constructor(
    private val spec: Spec?,
    private val endpointOverride: WirespecTestContext?,
    private val channelOverride: WirespecChannelContext?,
    val rng: RandomSourceHolder,
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<WirespecAmbient>

    private val endpoint: WirespecTestContext? by lazy {
        endpointOverride ?: spec?.let { s -> ContextRegistry.providers.firstNotNullOfOrNull { it.endpointContext(s) } }
    }

    private val channel: WirespecChannelContext? by lazy {
        channelOverride ?: spec?.let { s -> ContextRegistry.providers.firstNotNullOfOrNull { it.channelContext(s) } }
    }

    fun endpointContext(): WirespecTestContext = endpoint ?: error(
        "No WirespecTestContext available" + (spec?.let { " for ${it::class.simpleName}" } ?: "") + ". " +
            "Add `io.kotest.extensions.wirespec:kotest-wirespec-spring` to the test classpath and " +
            "`@ApplyExtension(SpringRootTestExtension::class)` to the spec, or wrap calls in " +
            "withWirespec(ctx) { … }.",
    )

    fun channelContext(): WirespecChannelContext? = channel
}

/** Read the ambient element installed by [WirespecExtension] or [withWirespec]. */
internal suspend fun currentAmbient(): WirespecAmbient =
    coroutineContext[WirespecAmbient] ?: error(
        "No wirespec ambient context in scope. Mount @ApplyExtension(WirespecExtension::class) on the spec, " +
            "or wrap the calls in withWirespec(ctx) { … }.",
    )
