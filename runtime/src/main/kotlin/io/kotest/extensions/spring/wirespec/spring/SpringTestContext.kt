package io.kotest.extensions.spring.wirespec.spring

import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import community.flock.wirespec.kotlin.Wirespec
import org.springframework.boot.SpringApplication
import org.springframework.boot.web.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.web.reactive.function.client.WebClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.reflect.KClass

/**
 * Minimal Spring Boot lifecycle harness for `SpringScenarioSpec`.
 *
 * We don't use Spring's `TestContextManager` directly because it's deeply tied
 * to JUnit's lifecycle, and we don't use `kotest-extensions-spring` because the
 * 1.x line is wire-incompatible with Kotest 6 (see SpringScenarioSpec for the
 * rationale).
 *
 * Instead, we boot the application via [SpringApplication] with the web
 * environment set to a random port, capture the actual port via a Boot lifecycle
 * listener, and expose a configured [WebClient] + [Wirespec.Serialization]
 * pair. Tearing down disposes the context.
 *
 * Spec-scoped: one context per spec, reused across all scenarios and iterations.
 */
internal class SpringTestContext private constructor(
    val context: ConfigurableApplicationContext,
    val port: Int,
) : AutoCloseable {

    val webClient: WebClient by lazy { WebClient.create("http://localhost:$port") }
    val transportation: Wirespec.Transportation by lazy { WebClientTransportation(webClient) }

    val serialization: Wirespec.Serialization by lazy {
        runCatching { context.getBean(Wirespec.Serialization::class.java) }
            .getOrElse { WirespecSerialization(jacksonObjectMapper()) }
    }

    override fun close() {
        runCatching { context.close() }
    }

    companion object {
        fun boot(application: KClass<*>): SpringTestContext {
            var capturedPort = -1
            val app = SpringApplication(application.java).apply {
                setAdditionalProfiles("test")
                addListeners(
                    ApplicationListener<WebServerInitializedEvent> { event ->
                        capturedPort = event.webServer.port
                    },
                )
            }
            val context = app.run("--server.port=0", "--spring.main.web-application-type=reactive")
            check(capturedPort > 0) {
                "Boot completed but no WebServerInitializedEvent was observed — is ${application.simpleName} a reactive (WebFlux) Spring Boot app?"
            }
            return SpringTestContext(context, capturedPort)
        }
    }
}
