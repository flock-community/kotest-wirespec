package io.kotest.extensions.wirespec

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.wirespec.context.ContextRegistry
import io.kotest.extensions.wirespec.dsl.ScenarioBuilder
import io.kotest.property.RandomSource
import io.kotest.property.checkAll

/**
 * Base spec for Wirespec scenarios. Framework-neutral — context providers (e.g.
 * the spring module's `SpringContextProvider`) register via `ServiceLoader`
 * and auto-supply the [endpointCtx] / [channelCtx] and any Kotest
 * [io.kotest.core.extensions.SpecExtension] they need mounted (the spring
 * provider mounts the upstream `SpringRootTestExtension` so
 * `@SpringBootTest`-annotated subclasses just work).
 *
 * Override [endpointCtx] / [channelCtx] to supply transports yourself
 * (e.g. against Testcontainers, or a plain `WebClient` against
 * `@LocalServerPort`).
 *
 * ```
 * @SpringBootTest(classes = [App::class])
 * @AutoConfigureMockMvc
 * class PetSpec : WirespecSpec({
 *     test("pet CRUD", iterations = 10) {
 *         val petId = createPet.returning<CreatePet.Response201, String> { it.body.id }
 *         getPet.path(petId).expecting<GetPet.Response200>()
 *     }
 * }) {
 *     @Autowired
 *     protected lateinit var applicationContext: ApplicationContext
 * }
 * ```
 */
abstract class WirespecSpec(body: WirespecSpec.() -> Unit = {}) : FunSpec() {

    init {
        ContextRegistry.providers
            .mapNotNull { it.specExtension() }
            .forEach { extension(it) }
        body()
    }

    open val endpointCtx: WirespecTestContext by lazy {
        ContextRegistry.providers.firstNotNullOfOrNull { it.endpointContext(this) }
            ?: error(
                "No WirespecTestContext available for ${this::class.simpleName}. " +
                    "Either override `endpointCtx` on the spec, or add " +
                    "`io.kotest.extensions.wirespec:kotest-wirespec-spring` to the test " +
                    "classpath so the Spring context provider can resolve a MockMvc bean " +
                    "(requires @AutoConfigureMockMvc).",
            )
    }

    open val channelCtx: WirespecChannelContext? by lazy {
        ContextRegistry.providers.firstNotNullOfOrNull { it.channelContext(this) }
    }

    fun test(name: String, iterations: Int = 1, body: ScenarioBuilder.() -> Unit) {
        super.test(name) {
            if (iterations <= 1) {
                runScenarioOnce(endpointCtx, channelCtx, RandomSource.seeded(System.nanoTime()), body)
            } else {
                checkAll<Int>(iterations = iterations) {
                    runScenarioOnce(endpointCtx, channelCtx, randomSource(), body)
                }
            }
        }
    }
}
