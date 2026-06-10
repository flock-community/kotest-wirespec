package io.kotest.extensions.wirespec

import io.kotest.extensions.wirespec.runtime.RandomSourceHolder
import io.kotest.extensions.wirespec.runtime.WirespecAmbient
import io.kotest.property.PropertyContext
import io.kotest.property.RandomSource
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Run [block] with an ambient wirespec context bound to an explicit [endpointCtx]
 * (and optional [channelCtx]). The replacement for `scenario(ctx) { }` on
 * non-kotest runners (e.g. JUnit) and anywhere you want to supply the context
 * yourself. [seed] seeds the per-test [RandomSource]; pass a fixed value to
 * reproduce a previously failing run.
 */
suspend fun <T> withWirespec(
    endpointCtx: WirespecTestContext,
    channelCtx: WirespecChannelContext? = null,
    seed: Long = System.nanoTime(),
    block: suspend () -> T,
): T {
    val ambient = WirespecAmbient(
        spec = null,
        endpointOverride = endpointCtx,
        channelOverride = channelCtx,
        rng = RandomSourceHolder(RandomSource.seeded(seed)),
    )
    return withContext(ambient) { block() }
}

/**
 * Opt-in property-run bridge. Call at the top of a `checkAll { }` block so the
 * wirespec generators draw from that iteration's [RandomSource] — making
 * kotest's reported seed reproduce wirespec-generated bodies too. Omit it to use
 * the per-test ambient source (still re-randomised each iteration as the source
 * advances).
 */
suspend fun PropertyContext.useWirespecSeed() {
    val ambient = coroutineContext[WirespecAmbient]
        ?: error("useWirespecSeed() requires an ambient wirespec context (WirespecExtension or withWirespec).")
    ambient.rng.randomSource = randomSource()
}
