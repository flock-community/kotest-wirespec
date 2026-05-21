package io.kotest.extensions.spring.wirespec.emitter

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
        emitted.file shouldBe "com/example/api/endpoint/NoSlotsDsl.kt"
        emitted.result shouldBe readGolden("NoSlotsDsl.kt")
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
        emitted.file shouldBe "com/example/api/endpoint/PetCreateDsl.kt"
        emitted.result shouldBe readGolden("PetCreateDsl.kt")
    }
})

private fun readGolden(name: String): String =
    DslFileEmitterTest::class.java.classLoader.getResource("golden/$name")!!.readText()
