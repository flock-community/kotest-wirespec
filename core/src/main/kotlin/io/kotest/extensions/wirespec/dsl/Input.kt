package io.kotest.extensions.wirespec.dsl

import io.kotest.property.Arb
import io.kotest.property.RandomSource

sealed class Input<T> {
    abstract fun resolve(rs: RandomSource): T

    data class Literal<T>(val value: T) : Input<T>() {
        override fun resolve(rs: RandomSource): T = value
    }

    data class FromArb<T>(val arb: Arb<T>) : Input<T>() {
        override fun resolve(rs: RandomSource): T = arb.sample(rs).value
    }

    class Lazy<T>(val builder: () -> T) : Input<T>() {
        override fun resolve(rs: RandomSource): T = builder()
    }
}
