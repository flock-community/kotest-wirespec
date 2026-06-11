package io.kotest.extensions.wirespec.spring

import community.flock.wirespec.kotlin.Wirespec
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import java.net.URI

/**
 * MockMvc-backed [Wirespec.Transportation]. Drives Spring's `DispatcherServlet`
 * in-process — no port binding, no real HTTP client — which makes it the right
 * default when a spec boots Spring purely to exercise its controllers.
 *
 * Use this when the spec is annotated with `@AutoConfigureMockMvc` (or
 * `@WebMvcTest`); the spring [ContextProvider]
 * [io.kotest.extensions.wirespec.context.ContextProvider] picks the bean up
 * automatically.
 */
class MockMvcTransportation(
    private val mockMvc: MockMvc,
) : Wirespec.Transportation {

    override suspend fun transport(request: Wirespec.RawRequest): Wirespec.RawResponse {
        val uri = URI.create("/" + request.path.joinToString("/"))
        val builder = MockMvcRequestBuilders.request(HttpMethod.valueOf(request.method), uri)

        request.queries
            .filterValues { it.isNotEmpty() }
            .forEach { (key, values) -> builder.queryParam(key, *values.toTypedArray()) }
        request.headers.forEach { (key, values) ->
            values.forEach { builder.header(key, it) }
        }
        request.body?.let {
            builder.contentType(MediaType.APPLICATION_JSON)
            builder.content(it)
        }

        // Spring MVC dispatches `suspend` controller methods asynchronously;
        // the first MockMvc result is just the "async started" placeholder.
        // Re-dispatch to drain the suspension's actual response.
        val initial = mockMvc.perform(builder).andReturn()
        val resolved = if (initial.request.isAsyncStarted) {
            mockMvc.perform(asyncDispatch(initial)).andReturn()
        } else {
            initial
        }
        val response = resolved.response
        val headers: MultiValueMap<String, String> = LinkedMultiValueMap()
        response.headerNames.forEach { name ->
            response.getHeaders(name).forEach { value -> headers.add(name, value) }
        }
        val raw = response.contentAsByteArray
        return Wirespec.RawResponse(
            statusCode = response.status,
            headers = headers,
            body = if (raw.isEmpty()) null else raw,
        )
    }
}
