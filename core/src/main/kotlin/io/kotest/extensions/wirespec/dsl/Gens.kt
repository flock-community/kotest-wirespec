package io.kotest.extensions.wirespec.dsl

import io.kotest.property.Arb
import io.kotest.property.Exhaustive
import io.kotest.property.Gen

/**
 * Narrow a [Gen] to an [Arb] for the slot setters that still require one
 * (the Wirespec `registerPath`/`body` overrides accept `() -> Arb<*>`).
 *
 * The generated scenario DSL exposes body/payload field overrides as [Gen] so
 * callers can supply either an [Arb] or an [Exhaustive]; [Exhaustive]s are
 * adapted via [Exhaustive.toArb].
 */
public fun <T> Gen<T>.asArb(): Arb<T> = when (this) {
    is Arb -> this
    is Exhaustive -> toArb()
}
