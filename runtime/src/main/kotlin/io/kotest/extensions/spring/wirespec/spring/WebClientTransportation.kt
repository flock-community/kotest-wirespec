package io.kotest.extensions.spring.wirespec.spring

import community.flock.wirespec.kotlin.Wirespec
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.util.CollectionUtils.toMultiValueMap
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Spring `WebClient`-backed [Wirespec.Transportation].
 *
 * The full response body is buffered into a `ByteArray` so the generated
 * `fromRawResponse` deserializer can read it as a single chunk. For streaming
 * endpoints (SSE / NDJSON) prefer [StreamingWebClientTransportation], which
 * collects N events or runs for a fixed duration.
 *
 * 4xx/5xx responses are folded back into a successful [Wirespec.RawResponse]
 * via `onErrorResume` so the DSL can still inspect the typed error variant.
 */
class WebClientTransportation(
    private val client: WebClient,
) : Wirespec.Transportation {

    override suspend fun transport(request: Wirespec.RawRequest): Wirespec.RawResponse = client
        .method(HttpMethod.valueOf(request.method))
        .uri { builder ->
            builder
                .path(request.path.joinToString("/"))
                .apply {
                    request.queries
                        .filterValues { it.isNotEmpty() }
                        .forEach { (key, value) -> queryParam(key, value) }
                }
                .build()
        }
        .apply {
            request.headers.forEach { (key, value) -> header(key, *value.toTypedArray()) }
            request.body?.let {
                contentType(MediaType.APPLICATION_JSON)
                bodyValue(it)
            }
        }
        .exchangeToMono { response ->
            response.bodyToMono(ByteArray::class.java)
                .map { body ->
                    Wirespec.RawResponse(
                        statusCode = response.statusCode().value(),
                        headers = toMultiValueMap(response.headers().asHttpHeaders()),
                        body = body,
                    )
                }
                .switchIfEmpty(
                    Mono.just(
                        Wirespec.RawResponse(
                            statusCode = response.statusCode().value(),
                            headers = toMultiValueMap(response.headers().asHttpHeaders()),
                            body = null,
                        ),
                    ),
                )
        }
        .onErrorResume { throwable ->
            when (throwable) {
                is WebClientResponseException -> Mono.just(
                    Wirespec.RawResponse(
                        statusCode = throwable.statusCode.value(),
                        headers = toMultiValueMap(throwable.headers),
                        body = throwable.responseBodyAsByteArray,
                    ),
                )
                else -> Mono.error(throwable)
            }
        }
        .awaitSingle()
}

/**
 * Reads the first chunk of the response body and drops the rest. Useful for
 * SSE / NDJSON streams that would otherwise keep the test thread parked until
 * the WebClient timeout fires.
 */
internal class HeadOnlyTransportation(
    private val client: WebClient,
    private val timeout: Duration = Duration.ofSeconds(3),
) : Wirespec.Transportation {

    override suspend fun transport(request: Wirespec.RawRequest): Wirespec.RawResponse = client
        .method(HttpMethod.valueOf(request.method))
        .uri { builder ->
            builder
                .path(request.path.joinToString("/"))
                .apply {
                    request.queries
                        .filterValues { it.isNotEmpty() }
                        .forEach { (key, value) -> queryParam(key, value) }
                }
                .build()
        }
        .apply {
            request.headers.forEach { (key, value) -> header(key, *value.toTypedArray()) }
            request.body?.let {
                contentType(MediaType.APPLICATION_JSON)
                bodyValue(it)
            }
        }
        .exchangeToMono { response ->
            val status = response.statusCode().value()
            val headers = toMultiValueMap(response.headers().asHttpHeaders())
            response.bodyToFlux(DataBuffer::class.java)
                .doOnNext(DataBufferUtils::release)
                .take(1)
                .then(Mono.just(Wirespec.RawResponse(status, headers, null)))
                .defaultIfEmpty(Wirespec.RawResponse(status, headers, null))
        }
        .timeout(timeout)
        .awaitSingle()
}
