package io.kotest.extensions.spring.wirespec.kotest

import io.kotest.core.extensions.SpecExtension
import io.kotest.core.spec.Spec
import io.kotest.extensions.spring.SpringTestExtension
import io.kotest.extensions.spring.SpringTestLifecycleMode

/**
 * Spec-scoped Spring lifecycle for Kotest 6. Mounts via `extension(...)` and
 * delegates the actual Spring work to the official
 * `io.kotest.extensions.spring.SpringTestExtension`: it boots the
 * `@SpringBootTest`-annotated application context before the spec runs, calls
 * `prepareTestInstance` so `@LocalServerPort` / `@Autowired` fields on the spec
 * instance get populated, and tears the context down afterwards.
 *
 * Why a wrapper exists at all: kotest-extensions-spring 1.3.0 was compiled
 * against Kotest 5.x. Its [SpringTestExtension] class implements both
 * [SpecExtension] (which we want) and `TestCaseExtension` (the per-test path),
 * and the per-test path references symbols Kotest 6 removed —
 * `io.kotest.core.names.TestName.getTestName()` and
 * `io.kotest.core.test.TestResult`. Registering the raw `SpringTestExtension`
 * therefore fails with `NoSuchMethodError` / `NoClassDefFoundError` on the
 * first test invocation. Subclassing-with-override doesn't compile because the
 * overridden signature references the missing `TestResult` class. Exposing
 * only the [SpecExtension] surface sidesteps both problems while still using
 * the maintained extension for everything that actually runs.
 *
 * For `@SpringBootTest(webEnvironment = RANDOM_PORT)` the spec-level
 * `prepareTestInstance` is enough: `@LocalServerPort` is constant across the
 * spec, so per-test re-injection is unnecessary.
 *
 * Drop this wrapper (and the `kotest-framework-api` exclusion in
 * `runtime/build.gradle.kts`) once kotest-extensions-spring publishes a Kotest
 * 6-compatible release.
 */
object SpringSpecExtension : SpecExtension {

    private val delegate = SpringTestExtension(SpringTestLifecycleMode.Root)

    override suspend fun intercept(spec: Spec, execute: suspend (Spec) -> Unit) {
        delegate.intercept(spec, execute)
    }
}
