package io.kotest.extensions.spring.wirespec

import io.kotest.extensions.spring.wirespec.dsl.ArbReceiver
import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.spring.wirespec.runtime.ScenarioRunner
import io.kotest.property.PropertyContext
import io.kotest.property.RandomSource

/**
 * Run a single iteration of the scenario DSL against [ctx].
 *
 * Intended to be called inside kotest-property's `checkAll { … }` so the
 * per-iteration [RandomSource] flows in via [PropertyContext.randomSource].
 * On failure, `checkAll` reports the failing seed so the run is reproducible.
 *
 * ```
 * checkAll(iterations = 10) {
 *     scenario(ctx) {
 *         createPet.expecting<CreatePet.Response201>()
 *         …
 *     }
 * }
 * ```
 */
suspend fun PropertyContext.scenario(
    ctx: WirespecTestContext,
    block: ScenarioBuilder.() -> Unit,
) {
    val rs = randomSource()
    runScenarioOnce(ctx, rs, block)
}

/**
 * Single-run overload for one-shot scenarios outside `checkAll` (smoke tests,
 * deterministic regressions). Seed defaults to [System.nanoTime] — pass a fixed
 * seed to reproduce a previously-failing run.
 */
suspend fun scenario(
    ctx: WirespecTestContext,
    seed: Long = System.nanoTime(),
    block: ScenarioBuilder.() -> Unit,
) {
    runScenarioOnce(ctx, RandomSource.seeded(seed), block)
}

private suspend fun runScenarioOnce(
    ctx: WirespecTestContext,
    rs: RandomSource,
    block: ScenarioBuilder.() -> Unit,
) {
    val arb = ArbReceiver(rs)
    val builder = ScenarioBuilder(arb).apply(block)
    try {
        ScenarioRunner(
            scenario = builder,
            transportation = ctx.transportation,
            serialization = ctx.serialization,
            randomSource = rs,
            arbReceiver = arb,
        ).run()
    } finally {
        builder.clearRefs()
    }
}
