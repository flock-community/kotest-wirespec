package io.kotest.extensions.spring.wirespec.emitter

import community.flock.wirespec.compiler.core.FileUri
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import community.flock.wirespec.compiler.core.parse.ast.Module
import community.flock.wirespec.compiler.core.parse.ast.Root
import community.flock.wirespec.compiler.utils.noLogger
import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain

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

        val emitter = TypesafeDslEmitter(PackageName("com.example.api"))
        val emitted = emitter.emit(ast, noLogger)

        val files = emitted.toList().map { it.file }
        files shouldContain "com/example/api/endpoint/PetCreateDsl.kt"
        // base emitter still produces its endpoint file
        files shouldContain "com/example/api/endpoint/PetCreate.kt"
    }
})
