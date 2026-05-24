package io.kotest.extensions.spring.wirespec.kotest

import io.kotest.core.extensions.MountableExtension
import io.kotest.core.listeners.AfterSpecListener
import io.kotest.core.listeners.BeforeSpecListener
import io.kotest.core.spec.Spec
import io.kotest.extensions.spring.wirespec.WirespecTestContext
import io.kotest.extensions.spring.wirespec.spring.SpringTestContext
import kotlin.reflect.KClass

/**
 * Kotest spec-scoped extension that boots a Spring Boot application on a random
 * port before the spec runs and tears it down afterwards.
 *
 * Mount once per spec (any spec style works — FunSpec, BehaviorSpec, …):
 * ```
 * class MySpec : FunSpec({
 *     val ws = install(SpringWirespecExtension(MyApp::class))
 *
 *     test("…") {
 *         checkAll<Int>(iterations = 10) {
 *             scenario(ws.context) { … }
 *         }
 *     }
 * })
 * ```
 *
 * Access the booted [WirespecTestContext] via [context] inside tests.
 */
class SpringWirespecExtension(
    private val application: KClass<*>,
) : MountableExtension<Unit, SpringWirespecExtension>, BeforeSpecListener, AfterSpecListener {

    private lateinit var spring: SpringTestContext

    /** Booted context: transportation + serialization. Access only inside tests. */
    val context: WirespecTestContext
        get() = WirespecTestContext(spring.transportation, spring.serialization)

    /** `install(...)` hook — returns this extension; we expose no configuration block. */
    override fun mount(configure: Unit.() -> Unit): SpringWirespecExtension = this

    override suspend fun beforeSpec(spec: Spec) {
        spring = SpringTestContext.boot(application)
    }

    override suspend fun afterSpec(spec: Spec) {
        if (::spring.isInitialized) spring.close()
    }
}
