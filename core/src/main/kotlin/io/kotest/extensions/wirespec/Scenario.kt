package io.kotest.extensions.wirespec

import io.kotest.extensions.wirespec.dsl.ArbReceiver
import io.kotest.extensions.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.wirespec.runtime.ScenarioRunner
import io.kotest.property.PropertyContext
import io.kotest.property.RandomSource

/**
 * Run a single iteration of the scenario DSL against [endpointCtx] (and
 * optionally [channelCtx] for channel steps).
 *
 * Intended to be called inside kotest-property's `checkAll { … }` so the
 * per-iteration [RandomSource] flows in via [PropertyContext.randomSource].
 * On failure, `checkAll` reports the failing seed so the run is reproducible.
 */
suspend fun PropertyContext.scenario(
    endpointCtx: WirespecTestContext,
    channelCtx: WirespecChannelContext? = null,
    block: ScenarioBuilder.() -> Unit,
) {
    val rs = randomSource()
    runScenarioOnce(endpointCtx, channelCtx, rs, block)
}

/**
 * Single-run overload for one-shot scenarios outside `checkAll` (smoke tests,
 * deterministic regressions). Seed defaults to [System.nanoTime] — pass a fixed
 * seed to reproduce a previously-failing run.
 */
suspend fun scenario(
    endpointCtx: WirespecTestContext,
    channelCtx: WirespecChannelContext? = null,
    seed: Long = System.nanoTime(),
    block: ScenarioBuilder.() -> Unit,
) {
    runScenarioOnce(endpointCtx, channelCtx, RandomSource.seeded(seed), block)
}

internal fun runScenarioOnce(
    endpointCtx: WirespecTestContext,
    channelCtx: WirespecChannelContext?,
    rs: RandomSource,
    block: ScenarioBuilder.() -> Unit,
) {
    val arb = ArbReceiver(rs)
    val builder = ScenarioBuilder(arb).apply(block)
    try {
        ScenarioRunner(
            scenario = builder,
            endpointCtx = endpointCtx,
            channelCtx = channelCtx,
            randomSource = rs,
            arbReceiver = arb,
        ).run()
    } finally {
        builder.clearRefs()
    }
}
