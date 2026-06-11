package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import community.flock.wirespec.compiler.core.parse.ast.Field
import community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Refined
import community.flock.wirespec.compiler.core.parse.ast.Type
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
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

    test("modelImports includes custom types referenced by body fields") {
        val createPetRequest = Type(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("CreatePetRequest"),
            shape = Type.Shape(
                value = listOf(
                    Field(emptyList(), FieldIdentifier("tag"), Reference.Custom("Tag", false)),
                    Field(emptyList(), FieldIdentifier("toys"), Reference.Iterable(Reference.Custom("Toy", false), false)),
                    Field(emptyList(), FieldIdentifier("name"), Reference.Primitive(Reference.Primitive.Type.String(null), false)),
                ),
            ),
            extends = emptyList(),
        )
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

        val shape = EndpointShape.from(endpoint, types = mapOf("CreatePetRequest" to createPetRequest))

        shape.modelImports shouldContain "CreatePetRequest"
        shape.modelImports shouldContain "Tag"
        shape.modelImports shouldContain "Toy"
    }

    test("body field whose ref is a Refined uses the refined's base type as kotlinType") {
        val refinedName = Refined(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("RefinedName"),
            reference = Reference.Primitive(Reference.Primitive.Type.String(null), false),
        )
        val createPet = Type(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("CreatePet"),
            shape = Type.Shape(
                value = listOf(
                    Field(emptyList(), FieldIdentifier("name"), Reference.Custom("RefinedName", false)),
                ),
            ),
            extends = emptyList(),
        )
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
                        reference = Reference.Custom("CreatePet", isNullable = false),
                    ),
                ),
            ),
            responses = emptyList(),
        )

        val shape = EndpointShape.from(
            endpoint,
            types = mapOf("CreatePet" to createPet),
            refined = mapOf("RefinedName" to refinedName),
        )

        shape.bodyFields.single().name shouldBe "name"
        shape.bodyFields.single().kotlinType shouldBe "String"
    }

    test("Iterable<Custom> body — bodyKind is List, bodyFields come from element type") {
        val stringRef = Reference.Primitive(Reference.Primitive.Type.String(null), false)
        val pet = Type(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("Pet"),
            shape = Type.Shape(listOf(Field(emptyList(), FieldIdentifier("name"), stringRef))),
            extends = emptyList(),
        )
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetCreateBulk"),
            method = Endpoint.Method.POST,
            path = emptyList(),
            queries = emptyList(),
            headers = emptyList(),
            requests = listOf(
                Endpoint.Request(
                    content = Endpoint.Content(
                        "application/json",
                        Reference.Iterable(Reference.Custom("Pet", false), isNullable = false),
                    ),
                ),
            ),
            responses = emptyList(),
        )

        val shape = EndpointShape.from(endpoint, types = mapOf("Pet" to pet))

        shape.bodyKind shouldBe EndpointShape.BodyKind.List
        shape.bodyType shouldBe "List<Pet>"
        shape.bodyElementType shouldBe "Pet"
        shape.bodyFields.map { it.name } shouldBe listOf("name")
    }

    test("Custom body — bodyKind is Object, bodyFields come from the type itself") {
        val stringRef = Reference.Primitive(Reference.Primitive.Type.String(null), false)
        val createReq = Type(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("CreatePetRequest"),
            shape = Type.Shape(listOf(Field(emptyList(), FieldIdentifier("name"), stringRef))),
            extends = emptyList(),
        )
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetCreate"),
            method = Endpoint.Method.POST,
            path = emptyList(),
            queries = emptyList(),
            headers = emptyList(),
            requests = listOf(
                Endpoint.Request(
                    content = Endpoint.Content(
                        "application/json",
                        Reference.Custom("CreatePetRequest", false),
                    ),
                ),
            ),
            responses = emptyList(),
        )

        val shape = EndpointShape.from(endpoint, types = mapOf("CreatePetRequest" to createReq))

        shape.bodyKind shouldBe EndpointShape.BodyKind.Object
        shape.bodyType shouldBe "CreatePetRequest"
        shape.bodyElementType shouldBe "CreatePetRequest"
        shape.bodyFields.map { it.name } shouldBe listOf("name")
    }
})
