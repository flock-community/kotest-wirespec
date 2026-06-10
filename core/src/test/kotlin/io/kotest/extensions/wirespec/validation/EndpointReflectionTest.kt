package io.kotest.extensions.wirespec.validation

import community.flock.wirespec.kotlin.Wirespec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private object TestEndpoint : Wirespec.Endpoint {
    object Handler

    data class Path(val unused: Unit = Unit) : Wirespec.Path
    data class Queries(val unused: Unit = Unit) : Wirespec.Queries
    data class RequestHeaders(val unused: Unit = Unit) : Wirespec.Request.Headers

    class Request(override val body: List<String>) : Wirespec.Request<List<String>> {
        override val path: Wirespec.Path = Path()
        override val method: Wirespec.Method = Wirespec.Method.POST
        override val queries: Wirespec.Queries = Queries()
        override val headers: Wirespec.Request.Headers = RequestHeaders()
    }

    abstract class Response<T : Any>(override val status: Int) : Wirespec.Response<T> {
        override val headers: Wirespec.Response.Headers = object : Wirespec.Response.Headers {}
    }
    class Response201 : Response<Unit>(201) { override val body: Unit = Unit }

    @JvmStatic
    fun fromResponse(serialization: Wirespec.Serialization, response: Wirespec.RawResponse): Response<*> = Response201()
}

private object NoBodyEndpoint : Wirespec.Endpoint {
    object Handler
    data class Path(val unused: Unit = Unit) : Wirespec.Path
    data class Queries(val unused: Unit = Unit) : Wirespec.Queries
    data class RequestHeaders(val unused: Unit = Unit) : Wirespec.Request.Headers

    class Request : Wirespec.Request<Unit> {
        override val body: Unit = Unit
        override val path: Wirespec.Path = Path()
        override val method: Wirespec.Method = Wirespec.Method.GET
        override val queries: Wirespec.Queries = Queries()
        override val headers: Wirespec.Request.Headers = RequestHeaders()
    }

    abstract class Response<T : Any>(override val status: Int) : Wirespec.Response<T> {
        override val headers: Wirespec.Response.Headers = object : Wirespec.Response.Headers {}
    }
    class Response200 : Response<Unit>(200) { override val body: Unit = Unit }

    @JvmStatic
    fun fromResponse(serialization: Wirespec.Serialization, response: Wirespec.RawResponse): Response<*> = Response200()
}

private object ScalarBodyEndpoint : Wirespec.Endpoint {
    object Handler
    data class Path(val unused: Unit = Unit) : Wirespec.Path
    data class Queries(val unused: Unit = Unit) : Wirespec.Queries
    data class RequestHeaders(val unused: Unit = Unit) : Wirespec.Request.Headers

    class Request(override val body: String) : Wirespec.Request<String> {
        override val path: Wirespec.Path = Path()
        override val method: Wirespec.Method = Wirespec.Method.POST
        override val queries: Wirespec.Queries = Queries()
        override val headers: Wirespec.Request.Headers = RequestHeaders()
    }

    abstract class Response<T : Any>(override val status: Int) : Wirespec.Response<T> {
        override val headers: Wirespec.Response.Headers = object : Wirespec.Response.Headers {}
    }
    class Response201 : Response<Unit>(201) { override val body: Unit = Unit }

    @JvmStatic
    fun fromResponse(serialization: Wirespec.Serialization, response: Wirespec.RawResponse): Response<*> = Response201()
}

private object ManyParamsEndpoint : Wirespec.Endpoint {
    object Handler
    data class Path(val agentId: String) : Wirespec.Path
    data class Queries(
        val page: Long?,
        val size: Long?,
        val query: String?,
        val sort: String?,
        val order: String?,
        val configVersionId: String?,
    ) : Wirespec.Queries
    object RequestHeaders : Wirespec.Request.Headers

    data class Request(
        override val path: Path,
        override val method: Wirespec.Method,
        override val queries: Queries,
        override val headers: RequestHeaders,
        override val body: Unit,
    ) : Wirespec.Request<Unit> {
        constructor(
            agentId: String,
            page: Long?,
            size: Long?,
            query: String?,
            sort: String?,
            order: String?,
            configVersionId: String?,
        ) : this(Path(agentId), Wirespec.Method.GET, Queries(page, size, query, sort, order, configVersionId), RequestHeaders, Unit)
    }

    abstract class Response<T : Any>(override val status: Int) : Wirespec.Response<T> {
        override val headers: Wirespec.Response.Headers = object : Wirespec.Response.Headers {}
    }
    class Response200 : Response<Unit>(200) { override val body: Unit = Unit }

    @JvmStatic
    fun fromResponse(serialization: Wirespec.Serialization, response: Wirespec.RawResponse): Response<*> = Response200()
}

class EndpointReflectionTest : FunSpec({

    test("List<String> body parameter — bodyElementClass is String") {
        val reflection = EndpointReflection.of(TestEndpoint)
        reflection.bodyElementClass shouldBe String::class.java
    }

    test("no body — bodyElementClass is null") {
        val reflection = EndpointReflection.of(NoBodyEndpoint)
        reflection.bodyElementClass.shouldBeNull()
    }

    test("scalar body parameter (String) — bodyElementClass is null") {
        val reflection = EndpointReflection.of(ScalarBodyEndpoint)
        reflection.bodyElementClass.shouldBeNull()
    }

    test("flattened secondary constructor with more params than the primary is still preferred") {
        // GetRunList-style endpoint: 1 path + 6 query params flatten into a
        // 7-arg secondary, while the primary has 5. Fewest-params selection
        // would wrongly pick the primary and treat `body: Unit` as a body slot.
        val reflection = EndpointReflection.of(ManyParamsEndpoint)
        reflection.hasBody shouldBe false
        reflection.requestConstructorParamNames shouldBe
            listOf("agentId", "page", "size", "query", "sort", "order", "configVersionId")
    }
})
