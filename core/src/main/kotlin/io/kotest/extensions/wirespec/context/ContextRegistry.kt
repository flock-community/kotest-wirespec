package io.kotest.extensions.wirespec.context

import java.util.ServiceLoader

/**
 * Lazy snapshot of [ContextProvider]s discovered via `ServiceLoader` at the
 * first call. Lookup order follows classpath order; the first non-null
 * `endpointContext` / `channelContext` result wins. With one provider on the
 * classpath today (spring), ordering is moot — revisit if a second provider
 * ever ships in the same artifact set.
 *
 * Internal to keep the surface tight; the public entry point is
 * [io.kotest.extensions.wirespec.WirespecSpec].
 */
internal object ContextRegistry {
    val providers: List<ContextProvider> by lazy {
        ServiceLoader.load(
            ContextProvider::class.java,
            ContextProvider::class.java.classLoader,
        ).toList()
    }
}
