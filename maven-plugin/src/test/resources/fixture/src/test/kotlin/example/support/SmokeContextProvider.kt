package example.support

import community.flock.wirespec.integration.kotest.WirespecTestContext
import community.flock.wirespec.integration.kotest.context.ContextProvider
import io.kotest.core.spec.Spec

/**
 * Supplies the generated `*.call { … }` DSL with the endpoint context from the shared
 * [SmokeEnvironment]. Discovered via `META-INF/services`.
 */
class SmokeContextProvider : ContextProvider {
    override fun endpointContext(spec: Spec): WirespecTestContext = SmokeEnvironment.endpointContext
}
