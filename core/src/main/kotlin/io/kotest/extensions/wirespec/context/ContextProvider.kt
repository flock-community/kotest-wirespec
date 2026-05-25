package io.kotest.extensions.wirespec.context

import io.kotest.core.extensions.SpecExtension
import io.kotest.core.spec.Spec
import io.kotest.extensions.wirespec.WirespecChannelContext
import io.kotest.extensions.wirespec.WirespecTestContext

/**
 * Service-loader-discovered hook that supplies framework-specific transports
 * and lifecycle to [io.kotest.extensions.wirespec.WirespecSpec].
 *
 * The spring module ships exactly one provider that registers itself via
 * `META-INF/services/io.kotest.extensions.wirespec.context.ContextProvider`.
 * Core code never calls into a provider directly — every provider method is
 * optional and defaults to `null`, so adding a new context (e.g. for Ktor)
 * doesn't force existing providers to change.
 */
interface ContextProvider {
    /**
     * A Kotest [SpecExtension] that should be mounted on every [io.kotest.extensions.wirespec.WirespecSpec].
     * Used by the spring provider to install the upstream `SpringRootTestExtension`.
     */
    fun specExtension(): SpecExtension? = null

    /**
     * Resolve a default [WirespecTestContext] from the running spec instance.
     * Return `null` if this provider can't supply one (e.g. no MockMvc bean
     * available); the spec then tries the next provider or surfaces a clear
     * error.
     */
    fun endpointContext(spec: Spec): WirespecTestContext? = null

    /**
     * Resolve a default [WirespecChannelContext] from the running spec
     * instance. `null` is the common case — channel tests opt in by, e.g.,
     * annotating the spec with `@EmbeddedKafka`.
     */
    fun channelContext(spec: Spec): WirespecChannelContext? = null
}
