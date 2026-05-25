package io.kotest.extensions.spring.wirespec

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.wirespec.channel.EmbeddedKafkaMessageTransport
import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.spring.SpringRootTestExtension
import io.kotest.extensions.spring.wirespec.spring.MockMvcTransportation
import io.kotest.property.RandomSource
import io.kotest.property.checkAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.test.web.servlet.MockMvc

/**
 * Base spec for Wirespec scenarios against a Spring application.
 *
 * Mounts the Spring lifecycle ([SpringRootTestExtension]), auto-resolves a default
 * [WirespecTestContext] from the running Spring container (MockMvc-backed),
 * and exposes a [test] overload whose body's receiver is [ScenarioBuilder] —
 * so the generated endpoint DSL (`createPet`, `getPet`, …) is in scope
 * directly, without any `scenario(ctx) { … }` wrapper.
 *
 * ```
 * @SpringBootTest(classes = [App::class])
 * @AutoConfigureMockMvc
 * class PetScenariosSpec : SpringWirespecSpec({
 *     test("pet CRUD") {
 *         val petId = createPet.returning<CreatePet.Response201, String> { it.body.id }
 *         getPet.path(petId).expecting<GetPet.Response200>()
 *     }
 *
 *     test("property-based", iterations = 10) {
 *         createPet.expecting<CreatePet.Response201>()
 *     }
 *
 *     test("alt transport") {
 *         wirespec(myOtherCtx) {
 *             createPet.expecting<CreatePet.Response201>()
 *         }
 *     }
 * })
 * ```
 *
 * Override [endpointCtx] to swap the default endpoint transport (e.g. WebClient
 * on a `LocalServerPort`). Override [channelCtx] for a non-default messaging
 * transport (Testcontainers etc.).
 */
abstract class SpringWirespecSpec(body: SpringWirespecSpec.() -> Unit = {}) : FunSpec() {

    @Autowired
    protected lateinit var applicationContext: ApplicationContext

    init {
        extension(SpringRootTestExtension())
        body()
    }

    /**
     * Default endpoint context for tests in this spec. Auto-resolves a [MockMvc]
     * bean from the Spring [ApplicationContext] — make sure the spec has
     * `@AutoConfigureMockMvc` (or uses `@WebMvcTest`). Override to point at a
     * different transport.
     */
    open val endpointCtx: WirespecTestContext by lazy {
        val mvc = applicationContext.getBeanProvider(MockMvc::class.java).getIfAvailable()
            ?: error(
                "No MockMvc bean is available on the Spring ApplicationContext. " +
                    "Annotate the spec with @AutoConfigureMockMvc (or use @WebMvcTest), " +
                    "or override `endpointCtx` to supply a custom WirespecTestContext.",
            )
        WirespecTestContext(
            transportation = MockMvcTransportation(mvc),
            serialization = WirespecSerialization(jacksonObjectMapper()),
        )
    }

    /**
     * Default channel context for tests in this spec. Auto-resolves an
     * EmbeddedKafka-backed transport when `@EmbeddedKafka` has populated an
     * [org.springframework.kafka.test.EmbeddedKafkaBroker] bean. Returns
     * `null` otherwise; channel steps against a null context fail fast in the
     * runner with a clear remediation message.
     *
     * Override to wire a different transport (e.g. Testcontainers).
     */
    open val channelCtx: WirespecChannelContext? by lazy {
        runCatching {
            WirespecChannelContext(
                messaging = EmbeddedKafkaMessageTransport(applicationContext),
                serialization = WirespecSerialization(jacksonObjectMapper()),
            )
        }.getOrNull()
    }

    /**
     * Register a Kotest test whose body is a Wirespec scenario.
     *
     * Each iteration runs as a single scenario against [endpointCtx] +
     * [channelCtx]. To use a different context for a particular test, wrap the
     * body in [wirespec][io.kotest.extensions.spring.wirespec.wirespec]:
     *
     * ```
     * test("alt transport") {
     *     wirespec(otherCtx) { createPet.expecting<...>() }
     * }
     * ```
     *
     * @param iterations how many scenario iterations to run; > 1 drives via
     *   kotest-property's `checkAll`, so failing seeds are reported and
     *   reproducible.
     */
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

/**
 * Run [block] as a sub-scenario with [ctx] in place of the surrounding
 * [SpringWirespecSpec.endpointCtx]. Executes synchronously, immediately —
 * intended as the override mechanism for individual tests:
 *
 * ```
 * test("alt transport") {
 *     wirespec(otherCtx) { createPet.expecting<...>() }
 * }
 * ```
 *
 * Mixing direct endpoint calls and `wirespec(ctx) { … }` in the same test body
 * works, but the override block runs eagerly while the surrounding calls run
 * once the test body returns — keep tests homogeneous to avoid surprising
 * execution order.
 */
fun ScenarioBuilder.wirespec(
    ctx: WirespecTestContext,
    channelCtx: WirespecChannelContext? = null,
    block: ScenarioBuilder.() -> Unit,
) {
    runScenarioOnce(ctx, channelCtx, RandomSource.seeded(System.nanoTime()), block)
}
