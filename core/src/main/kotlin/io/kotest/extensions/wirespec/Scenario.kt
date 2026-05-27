package io.kotest.extensions.wirespec

import io.kotest.core.test.TestScope
import io.kotest.extensions.wirespec.context.ContextRegistry
import io.kotest.extensions.wirespec.dsl.ArbReceiver
import io.kotest.extensions.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.wirespec.runtime.ScenarioRunner
import io.kotest.property.PropertyContext
import io.kotest.property.RandomSource
import io.kotest.property.checkAll

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

/**
 * Run a scenario inside a plain Kotest spec (`FunSpec`, `WordSpec`, …) with no
 * base class. The endpoint (and channel) context is auto-resolved from the
 * running spec via the [ContextProvider][io.kotest.extensions.wirespec.context.ContextProvider]
 * SPI — on the JVM, mounting `@ApplyExtension(SpringRootTestExtension::class)` and
 * adding `kotest-wirespec-spring` lets the spring provider resolve a `MockMvc`-backed
 * context by reflecting the spec's `@Autowired ApplicationContext`.
 *
 * When [iterations] > 1 the block is wrapped in kotest-property's `checkAll`, so a
 * failing run reports its seed for reproducibility; otherwise it runs once.
 */
suspend fun TestScope.scenario(
    iterations: Int = 1,
    block: ScenarioBuilder.() -> Unit,
) {
    val spec = testCase.spec
    val endpointCtx = ContextRegistry.providers.firstNotNullOfOrNull { it.endpointContext(spec) }
        ?: error(
            "No WirespecTestContext available for ${spec::class.simpleName}. " +
                "Add `io.kotest.extensions.wirespec:kotest-wirespec-spring` to the test classpath " +
                "and `@ApplyExtension(SpringRootTestExtension::class)` to the spec, or pass an " +
                "explicit context: scenario(ctx, iterations = …) { … }.",
        )
    val channelCtx = ContextRegistry.providers.firstNotNullOfOrNull { it.channelContext(spec) }
    runScenarioIterations(endpointCtx, channelCtx, iterations, block)
}

/**
 * Explicit-context overload of [scenario] for custom transports (e.g. a
 * `WebClient` against `@LocalServerPort`) — bypasses provider auto-resolution.
 */
suspend fun TestScope.scenario(
    endpointCtx: WirespecTestContext,
    channelCtx: WirespecChannelContext? = null,
    iterations: Int = 1,
    block: ScenarioBuilder.() -> Unit,
) {
    runScenarioIterations(endpointCtx, channelCtx, iterations, block)
}

private suspend fun runScenarioIterations(
    endpointCtx: WirespecTestContext,
    channelCtx: WirespecChannelContext?,
    iterations: Int,
    block: ScenarioBuilder.() -> Unit,
) {
    if (iterations <= 1) {
        runScenarioOnce(endpointCtx, channelCtx, RandomSource.seeded(System.nanoTime()), block)
    } else {
        checkAll<Int>(iterations = iterations) {
            runScenarioOnce(endpointCtx, channelCtx, randomSource(), block)
        }
    }
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
