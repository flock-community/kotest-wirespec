package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.FileUri
import community.flock.wirespec.compiler.core.emit.EmitShared
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import community.flock.wirespec.compiler.core.parse.ast.Module
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Root
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
})
