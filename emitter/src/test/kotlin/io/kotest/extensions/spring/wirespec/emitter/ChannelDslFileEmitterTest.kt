package io.kotest.extensions.spring.wirespec.emitter

import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Field
import community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Type
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ChannelDslFileEmitterTest : FunSpec({

    val pkg = PackageName("com.example.api")
    fun stringRef() = Reference.Primitive(Reference.Primitive.Type.String(null), false)

    test("simple-payload channel — only topic/send/expecting on the Call class") {
        val channel = Channel(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("SimplePayloadChannel"),
            reference = stringRef(),
        )

        val emitted = ChannelDslFileEmitter.emit(channel, pkg)

        emitted.file shouldBe "com/example/api/kotest/SimplePayloadChannelDsl.kt"
        emitted.result shouldBe readGolden("SimplePayloadChannelDsl.kt")
    }

    test("custom-payload channel — payload body builder for send(block) overrides") {
        val petCreated = Type(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetCreated"),
            shape = Type.Shape(
                value = listOf(
                    Field(emptyList(), FieldIdentifier("id"), stringRef()),
                    Field(emptyList(), FieldIdentifier("name"), stringRef()),
                ),
            ),
            extends = emptyList(),
        )
        val channel = Channel(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetCreatedChannel"),
            reference = Reference.Custom("PetCreated", false),
        )

        val emitted = ChannelDslFileEmitter.emit(channel, pkg, types = mapOf("PetCreated" to petCreated))

        emitted.file shouldBe "com/example/api/kotest/PetCreatedChannelDsl.kt"
        emitted.result shouldBe readGolden("CustomPayloadChannelDsl.kt")
    }
})

private fun readGolden(name: String): String =
    ChannelDslFileEmitterTest::class.java.classLoader.getResource("golden/$name")!!.readText()
