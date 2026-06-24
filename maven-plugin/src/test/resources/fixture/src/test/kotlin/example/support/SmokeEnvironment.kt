package example.support

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.v2.kotlin.WirespecSerialization
import community.flock.wirespec.integration.kotest.WirespecTestContext
import community.flock.wirespec.kotlin.Wirespec
import example.ExampleApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext

/**
 * Process-wide test environment: one Spring app instance on a random port, started lazily on
 * first use and torn down by a JVM shutdown hook. Exposes the endpoint context that
 * [SmokeContextProvider] hands to the generated `*.call { … }` DSL.
 */
object SmokeEnvironment {

    private val application: ConfigurableApplicationContext by lazy {
        val context = SpringApplicationBuilder(ExampleApplication::class.java)
            .properties("server.port=0")
            .run()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { context.close() } })
        context
    }

    private val serialization: Wirespec.Serialization = WirespecSerialization(jacksonObjectMapper())

    val endpointContext: WirespecTestContext by lazy {
        val port = (application as ServletWebServerApplicationContext).webServer.port
        WirespecTestContext(
            transportation = HttpClientTransportation("http://localhost:$port"),
            serialization = serialization,
        )
    }
}
