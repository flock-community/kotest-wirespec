package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.FileUri
import community.flock.wirespec.compiler.core.emit.EmitShared
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import community.flock.wirespec.compiler.core.parse.ast.Field
import community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Module
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Root
import community.flock.wirespec.compiler.core.parse.ast.Type
import community.flock.wirespec.compiler.utils.noLogger
import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class TypesafeDslEmitterTest : FunSpec({

    test("emit appends one <Name>Dsl.kt per endpoint alongside base output") {
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
                        "application/json",
                        community.flock.wirespec.compiler.core.parse.ast.Reference.Custom("CreatePetRequest", false),
                    ),
                ),
            ),
            responses = emptyList(),
        )
        val module = Module(FileUri("mem://pets.ws"), nonEmptyListOf(endpoint))
        val ast = Root(nonEmptyListOf(module))

        val emitter = TypesafeDslEmitter(PackageName("com.example.api"), EmitShared())
        val emitted = emitter.emit(ast, noLogger)

        val files = emitted.toList().map { it.file }
        files shouldContain "com/example/api/kotest/PetCreateDsl.kt"
        // base emitter still produces its endpoint file
        files shouldContain "com/example/api/endpoint/PetCreate.kt"
    }

    test("emit appends one WirespecCatalog.kt aggregating every endpoint then channel") {
        fun endpoint(name: String) = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier(name),
            method = Endpoint.Method.GET,
            path = listOf(Endpoint.Segment.Literal("api")),
            queries = emptyList(),
            headers = emptyList(),
            requests = listOf(Endpoint.Request(content = null)),
            responses = emptyList(),
        )
        val channel = Channel(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetCreatedChannel"),
            reference = Reference.Primitive(Reference.Primitive.Type.String(null), false),
        )
        val module = Module(FileUri("mem://pets.ws"), nonEmptyListOf(endpoint("PetCreate"), endpoint("PetGet"), channel))
        val ast = Root(nonEmptyListOf(module))

        val emitter = TypesafeDslEmitter(PackageName("com.example.api"), EmitShared())
        val emitted = emitter.emit(ast, noLogger)

        val catalogs = emitted.toList().filter { it.file == "com/example/api/kotest/WirespecCatalog.kt" }
        catalogs.size shouldBe 1
        val catalog = catalogs.single().result
        catalog.contains("public val ScenarioBuilder.wirespec: WirespecCatalog") shouldBe true
        catalog.contains("public val petCreate: PetCreateCall") shouldBe true
        catalog.contains("public val petGet: PetGetCall") shouldBe true
        catalog.contains("public val petCreatedChannel: PetCreatedChannelCall") shouldBe true
    }

    test("contract with nested type bodies — emits per-field builders end-to-end") {
        // Sanity check that the catalog pipeline (TypesafeDslEmitter -> DslFileEmitter)
        // forwards module-level Type definitions so nested-custom-field bodies emit
        // both root and per-field BodyBuilder classes. Mirrors the
        // DslFileEmitterTest "nested object and nested list body fields" fixture,
        // but exercises the full Root/Module -> emit pipeline instead of calling
        // DslFileEmitter directly.
        val stringRef = Reference.Primitive(Reference.Primitive.Type.String(null), false)
        val ownerType = Type(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("Owner"),
            shape = Type.Shape(
                listOf(Field(emptyList(), FieldIdentifier("email"), stringRef)),
            ),
            extends = emptyList(),
        )
        val tagType = Type(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("Tag"),
            shape = Type.Shape(
                listOf(Field(emptyList(), FieldIdentifier("label"), stringRef)),
            ),
            extends = emptyList(),
        )
        val petType = Type(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("Pet"),
            shape = Type.Shape(
                listOf(
                    Field(emptyList(), FieldIdentifier("name"), stringRef),
                    Field(emptyList(), FieldIdentifier("owner"), Reference.Custom("Owner", false)),
                    Field(
                        emptyList(),
                        FieldIdentifier("tags"),
                        Reference.Iterable(Reference.Custom("Tag", false), isNullable = false),
                    ),
                ),
            ),
            extends = emptyList(),
        )
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetCreateNested"),
            method = Endpoint.Method.POST,
            path = listOf(Endpoint.Segment.Literal("api"), Endpoint.Segment.Literal("pets")),
            queries = emptyList(),
            headers = emptyList(),
            requests = listOf(
                Endpoint.Request(
                    content = Endpoint.Content("application/json", Reference.Custom("Pet", false)),
                ),
            ),
            responses = emptyList(),
        )
        val module = Module(FileUri("mem://pets.ws"), nonEmptyListOf(petType, ownerType, tagType, endpoint))
        val ast = Root(nonEmptyListOf(module))

        val emitter = TypesafeDslEmitter(PackageName("com.example.api"), EmitShared())
        val emitted = emitter.emit(ast, noLogger)

        // The endpoint Dsl file must be present (full pipeline produced it).
        val dslFile = emitted.toList().single { it.file == "com/example/api/kotest/PetCreateNestedDsl.kt" }
        val dslText = dslFile.result

        // Root body builder and per-nested-field body builders must all be
        // emitted into the same file. These are the surface that proves
        // DslFileEmitter's nested-type recursion (Task 3) is reachable through
        // the production TypesafeDslEmitter entry point.
        dslText.contains("public class PetCreateNestedCall") shouldBe true
        dslText.contains("public class PetBodyBuilder") shouldBe true
        dslText.contains("public class OwnerBodyBuilder") shouldBe true
        dslText.contains("public class TagBodyBuilder") shouldBe true
        // And the root builder must reference the nested builders so chained
        // configuration actually compiles for downstream users.
        dslText.contains("OwnerBodyBuilder.() -> Unit") shouldBe true
        dslText.contains("TagBodyBuilder.() -> Unit") shouldBe true
    }
})
