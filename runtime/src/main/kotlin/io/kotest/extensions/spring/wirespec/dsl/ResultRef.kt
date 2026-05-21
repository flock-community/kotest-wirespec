package io.kotest.extensions.spring.wirespec.dsl

class ResultRef<T> internal constructor(internal val label: String) {

    @Volatile
    private var resolved: Boolean = false

    @Volatile
    private var value: Any? = NOT_SET

    internal fun set(v: T) {
        value = v
        resolved = true
    }

    internal fun clear() {
        value = NOT_SET
        resolved = false
    }

    @Suppress("UNCHECKED_CAST")
    fun require(): T {
        check(resolved) {
            "ResultRef[$label] read before its producing endpoint call ran. " +
                "Did you reference a value from an endpoint that hasn't been declared yet in this scenario?"
        }
        return value as T
    }

    override fun toString(): String = "ResultRef[$label]"

    private companion object {
        val NOT_SET = Any()
    }
}
