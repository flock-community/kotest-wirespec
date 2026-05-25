package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import community.flock.wirespec.compiler.core.parse.ast.Field
import community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Reference
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EndpointShapeTest : FunSpec({

    test("petGet — path with one String segment, no body, no queries, no headers") {
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetGet"),
            method = Endpoint.Method.GET,
            path = listOf(
                Endpoint.Segment.Literal("api"),
                Endpoint.Segment.Literal("pets"),
                Endpoint.Segment.Param(FieldIdentifier("id"), Reference.Primitive(Reference.Primitive.Type.String(null), false)),
            ),
            queries = emptyList(),
            headers = emptyList(),
            requests = listOf(Endpoint.Request(content = null)),
            responses = emptyList(),
        )

        val shape = EndpointShape.from(endpoint)

        shape.name shouldBe "PetGet"
        shape.dslName shouldBe "petGet"
        shape.pathFields.map { it.name } shouldBe listOf("id")
        shape.pathFields.single().kotlinType shouldBe "String"
        shape.bodyType shouldBe null
        shape.queryFields shouldBe emptyList()
        shape.headerFields shouldBe emptyList()
    }

    test("petCreate — body, no path, no queries, no headers") {
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetCreate"),
            method = Endpoint.Method.POST,
            path = listOf(Endpoint.Segment.Literal("api"), Endpoint.Segment.Literal("pets")),
            queries = emptyList(),
            headers = emptyList(),
            requests = listOf(
                Endpoint.Request(
                    content = Endpoint.Content(
                        type = "application/json",
                        reference = Reference.Custom("CreatePetRequest", isNullable = false),
                    ),
                ),
            ),
            responses = emptyList(),
        )

        val shape = EndpointShape.from(endpoint)

        shape.bodyType shouldBe "CreatePetRequest"
        shape.pathFields shouldBe emptyList()
    }

    test("petList — queries with two Int fields") {
        val intRef = Reference.Primitive(
            Reference.Primitive.Type.Integer(Reference.Primitive.Type.Precision.P32, constraint = null),
            isNullable = false,
        )
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetList"),
            method = Endpoint.Method.GET,
            path = listOf(Endpoint.Segment.Literal("api"), Endpoint.Segment.Literal("pets")),
            queries = listOf(
                Field(emptyList(), FieldIdentifier("limit"), intRef),
                Field(emptyList(), FieldIdentifier("offset"), intRef),
            ),
            headers = emptyList(),
            requests = listOf(Endpoint.Request(content = null)),
            responses = emptyList(),
        )

        val shape = EndpointShape.from(endpoint)

        shape.queryFields.map { it.name } shouldBe listOf("limit", "offset")
        shape.queryFields.map { it.kotlinType } shouldBe listOf("Int", "Int")
    }
})
