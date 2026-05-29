package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DslFileEmitterTest : FunSpec({
    val pkg = PackageName("com.example.api")

    fun emptyEndpoint(name: String) = Endpoint(
        comment = null,
        annotations = emptyList(),
        identifier = DefinitionIdentifier(name),
        method = Endpoint.Method.GET,
        path = emptyList(),
        queries = emptyList(),
        headers = emptyList(),
        requests = listOf(Endpoint.Request(content = null)),
        responses = emptyList(),
    )

    test("no slots — only expecting/returning/collecting on the Call class") {
        val emitted = DslFileEmitter.emit(emptyEndpoint("NoSlots"), pkg)
        emitted.file shouldBe "com/example/api/kotest/NoSlotsDsl.kt"
        emitted.result shouldBe readGolden("NoSlotsDsl.kt")
    }

    test("path-only endpoint (PetGet) — typed path() + lazy + ResultRef overloads") {
        val stringRef = community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive(
            community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive.Type.String(null), false
        )
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetGet"),
            method = Endpoint.Method.GET,
            path = listOf(
                Endpoint.Segment.Literal("api"),
                Endpoint.Segment.Literal("pets"),
                Endpoint.Segment.Param(community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier("id"), stringRef),
            ),
            queries = emptyList(),
            headers = emptyList(),
            requests = listOf(Endpoint.Request(content = null)),
            responses = emptyList(),
        )

        val emitted = DslFileEmitter.emit(endpoint, pkg)
        emitted.file shouldBe "com/example/api/kotest/PetGetDsl.kt"
        emitted.result shouldBe readGolden("PetGetDsl.kt")
    }

    test("queries-only endpoint (PetList) — typed query(limit, offset)") {
        val intRef = community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive(
            community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive.Type.Integer(
                community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive.Type.Precision.P32, null
            ),
            false,
        )
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetList"),
            method = Endpoint.Method.GET,
            path = listOf(Endpoint.Segment.Literal("api"), Endpoint.Segment.Literal("pets")),
            queries = listOf(
                community.flock.wirespec.compiler.core.parse.ast.Field(emptyList(), community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier("limit"), intRef),
                community.flock.wirespec.compiler.core.parse.ast.Field(emptyList(), community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier("offset"), intRef),
            ),
            headers = emptyList(),
            requests = listOf(Endpoint.Request(content = null)),
            responses = emptyList(),
        )

        val emitted = DslFileEmitter.emit(endpoint, pkg)
        emitted.file shouldBe "com/example/api/kotest/PetListDsl.kt"
        emitted.result shouldBe readGolden("PetListDsl.kt")
    }

    test("headers-only endpoint — typed header(auth: String)") {
        val stringRef = community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive(
            community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive.Type.String(null), false
        )
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("HeaderEndpoint"),
            method = Endpoint.Method.GET,
            path = listOf(Endpoint.Segment.Literal("api")),
            queries = emptyList(),
            headers = listOf(
                community.flock.wirespec.compiler.core.parse.ast.Field(
                    emptyList(),
                    community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier("auth"),
                    stringRef,
                ),
            ),
            requests = listOf(Endpoint.Request(content = null)),
            responses = emptyList(),
        )

        val emitted = DslFileEmitter.emit(endpoint, pkg)
        emitted.file shouldBe "com/example/api/kotest/HeaderEndpointDsl.kt"
        emitted.result shouldBe readGolden("HeaderEndpointDsl.kt")
    }

    test("path + body endpoint (PetUpdate) — both slot renderers compose") {
        val stringRef = community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive(
            community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive.Type.String(null), false
        )
        val updateReq = community.flock.wirespec.compiler.core.parse.ast.Reference.Custom("UpdatePetRequest", false)
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetUpdate"),
            method = Endpoint.Method.PATCH,
            path = listOf(
                Endpoint.Segment.Literal("api"),
                Endpoint.Segment.Literal("pets"),
                Endpoint.Segment.Param(community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier("id"), stringRef),
            ),
            queries = emptyList(),
            headers = emptyList(),
            requests = listOf(Endpoint.Request(content = Endpoint.Content("application/json", updateReq))),
            responses = emptyList(),
        )

        val emitted = DslFileEmitter.emit(endpoint, pkg)
        emitted.result shouldBe readGolden("PetUpdateDsl.kt")
    }

    test("body-only endpoint (PetCreate) — body() overloads, no path/query/header") {
        val createReq = community.flock.wirespec.compiler.core.parse.ast.Reference.Custom("CreatePetRequest", isNullable = false)
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetCreate"),
            method = Endpoint.Method.POST,
            path = listOf(Endpoint.Segment.Literal("api"), Endpoint.Segment.Literal("pets")),
            queries = emptyList(),
            headers = emptyList(),
            requests = listOf(
                Endpoint.Request(content = Endpoint.Content("application/json", createReq)),
            ),
            responses = emptyList(),
        )

        val emitted = DslFileEmitter.emit(endpoint, pkg)
        emitted.file shouldBe "com/example/api/kotest/PetCreateDsl.kt"
        emitted.result shouldBe readGolden("PetCreateDsl.kt")
    }

    test("body-only endpoint with Iterable<Custom> body (PetCreateBulk) — body{} emits with wildcard prefix") {
        val stringRef = community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive(
            community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive.Type.String(null), false
        )
        val petType = community.flock.wirespec.compiler.core.parse.ast.Type(
            comment = null,
            annotations = emptyList(),
            identifier = community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier("Pet"),
            shape = community.flock.wirespec.compiler.core.parse.ast.Type.Shape(
                listOf(community.flock.wirespec.compiler.core.parse.ast.Field(emptyList(), community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier("name"), stringRef)),
            ),
            extends = emptyList(),
        )
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier("PetCreateBulk"),
            method = Endpoint.Method.POST,
            path = listOf(Endpoint.Segment.Literal("api"), Endpoint.Segment.Literal("pets")),
            queries = emptyList(),
            headers = emptyList(),
            requests = listOf(
                Endpoint.Request(
                    content = Endpoint.Content(
                        "application/json",
                        community.flock.wirespec.compiler.core.parse.ast.Reference.Iterable(
                            community.flock.wirespec.compiler.core.parse.ast.Reference.Custom("Pet", false),
                            isNullable = false,
                        ),
                    ),
                ),
            ),
            responses = emptyList(),
        )

        val emitted = DslFileEmitter.emit(endpoint, pkg, types = mapOf("Pet" to petType))
        emitted.file shouldBe "com/example/api/kotest/PetCreateBulkDsl.kt"
        emitted.result shouldBe readGolden("PetCreateBulkDsl.kt")
    }
})

private fun readGolden(name: String): String =
    DslFileEmitterTest::class.java.classLoader.getResource("golden/$name")!!.readText()
