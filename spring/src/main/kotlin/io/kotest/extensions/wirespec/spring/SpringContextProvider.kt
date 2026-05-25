package io.kotest.extensions.wirespec.spring

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import io.kotest.core.extensions.SpecExtension
import io.kotest.core.spec.Spec
import io.kotest.extensions.spring.SpringRootTestExtension
import io.kotest.extensions.wirespec.WirespecChannelContext
import io.kotest.extensions.wirespec.WirespecTestContext
import io.kotest.extensions.wirespec.context.ContextProvider
import org.springframework.context.ApplicationContext
import org.springframework.test.web.servlet.MockMvc
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * Default [ContextProvider] for Spring-based scenarios. Activated when this
 * module is on the test classpath via
 * `META-INF/services/io.kotest.extensions.wirespec.context.ContextProvider`.
 *
 * Resolution strategy:
 *   1. Mount the upstream [SpringRootTestExtension] so `@SpringBootTest` boots
 *      and `@Autowired` fields populate.
 *   2. For [endpointContext], reflect the spec for a property of type
 *      [ApplicationContext], look up a `MockMvc` bean, wrap it in
 *      [MockMvcTransportation]. Returns `null` if either step fails — the
 *      user can then override `endpointCtx` manually (e.g. to use a
 *      `LocalServerPort`-driven [WebClientTransportation]).
 *   3. For [channelContext], try to resolve an `EmbeddedKafkaBroker` bean
 *      via [EmbeddedKafkaMessageTransport]. Returns `null` if the
 *      `@EmbeddedKafka` setup isn't present.
 */
class SpringContextProvider : ContextProvider {

    override fun specExtension(): SpecExtension = SpringRootTestExtension()

    override fun endpointContext(spec: Spec): WirespecTestContext? {
        val app = applicationContextOf(spec) ?: return null
        val mvc = app.getBeanProvider(MockMvc::class.java).getIfAvailable() ?: return null
        return WirespecTestContext(
            transportation = MockMvcTransportation(mvc),
            serialization = WirespecSerialization(jacksonObjectMapper()),
        )
    }

    override fun channelContext(spec: Spec): WirespecChannelContext? {
        val app = applicationContextOf(spec) ?: return null
        // spring-kafka is compileOnly in :spring; absence at runtime is silent.
        return runCatching {
            WirespecChannelContext(
                messaging = EmbeddedKafkaMessageTransport(app),
                serialization = WirespecSerialization(jacksonObjectMapper()),
            )
        }.getOrNull()
    }

    private fun applicationContextOf(spec: Spec): ApplicationContext? {
        val match = spec::class.memberProperties.firstOrNull { property ->
            val classifier = property.returnType.classifier as? KClass<*> ?: return@firstOrNull false
            ApplicationContext::class.java.isAssignableFrom(classifier.java)
        } ?: return null
        match.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (match as KProperty1<Any, *>).get(spec) as? ApplicationContext
    }
}
