package io.kotest.extensions.wirespec

import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.test.TestCase
// kotest 6.x relocated TestResult here; io.kotest.core.test.TestResult does not exist in 6.1.11.
import io.kotest.engine.test.TestResult
import io.kotest.extensions.wirespec.runtime.RandomSourceHolder
import io.kotest.extensions.wirespec.runtime.WirespecAmbient
import io.kotest.property.RandomSource
import kotlinx.coroutines.withContext

/**
 * Installs an ambient wirespec context around every test so wrapper-free
 * `PetControllerV1.createPet…` calls resolve their HTTP/channel context (via the
 * [io.kotest.extensions.wirespec.context.ContextProvider] SPI) and a per-test
 * [RandomSource]. Mount with `@ApplyExtension(WirespecExtension::class)`.
 */
class WirespecExtension : TestCaseExtension {
    override suspend fun intercept(
        testCase: TestCase,
        execute: suspend (TestCase) -> TestResult,
    ): TestResult {
        val ambient = WirespecAmbient(
            spec = testCase.spec,
            endpointOverride = null,
            channelOverride = null,
            rng = RandomSourceHolder(RandomSource.seeded(System.nanoTime())),
        )
        return withContext(ambient) { execute(testCase) }
    }
}
