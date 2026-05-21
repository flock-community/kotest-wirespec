package io.kotest.extensions.spring.wirespec

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.wirespec.dsl.ArbReceiver
import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.spring.wirespec.runtime.ScenarioRunner
import io.kotest.extensions.spring.wirespec.spring.SpringTestContext
import io.kotest.property.RandomSource
import kotlin.reflect.KClass

abstract class SpringScenarioSpec(
    val application: KClass<*>,
    body: SpringScenarioSpec.() -> Unit = {},
) : FunSpec() {

    private lateinit var springContext: SpringTestContext

    init {
        beforeSpec { springContext = SpringTestContext.boot(application) }
        afterSpec { if (::springContext.isInitialized) springContext.close() }
        body()
    }

    fun scenario(
        name: String,
        iterations: Int = 100,
        seed: Long? = null,
        block: ScenarioBuilder.() -> Unit,
    ) {
        test(name) {
            val resolvedSeed = seed ?: System.nanoTime()
            for (iteration in 0 until iterations) {
                val rs = RandomSource.seeded(resolvedSeed + iteration)
                val arb = ArbReceiver(rs)
                val scenario = ScenarioBuilder(arb)
                scenario.block()
                try {
                    ScenarioRunner(
                        scenario = scenario,
                        transportation = springContext.transportation,
                        serialization = springContext.serialization,
                        randomSource = rs,
                        arbReceiver = arb,
                    ).run()
                } catch (t: Throwable) {
                    throw AssertionError(
                        "Scenario `$name` failed on iteration ${iteration + 1}/$iterations (seed=${resolvedSeed + iteration}): ${t.message}",
                        t,
                    )
                } finally {
                    scenario.clearRefs()
                }
            }
        }
    }
}
