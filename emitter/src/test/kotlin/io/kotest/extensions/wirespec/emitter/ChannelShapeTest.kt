package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Field
import community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Type
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ChannelShapeTest : FunSpec({

    fun stringRef() = Reference.Primitive(Reference.Primitive.Type.String(null), false)

    test("simple-payload channel — primitive String body") {
        val channel = Channel(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("HeartbeatChannel"),
            reference = stringRef(),
        )

        val shape = ChannelShape.from(channel)

        shape.name shouldBe "HeartbeatChannel"
        shape.dslName shouldBe "heartbeatChannel"
        shape.payloadType shouldBe "String"
        shape.payloadFields shouldBe emptyList()
        shape.modelImports shouldBe emptyList()
    }

    test("custom-payload channel — references a Type, populates payloadFields") {
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

        val shape = ChannelShape.from(channel, types = mapOf("PetCreated" to petCreated))

        shape.payloadType shouldBe "PetCreated"
        shape.payloadFields.map { it.name } shouldBe listOf("id", "name")
        shape.payloadFields.map { it.kotlinType } shouldBe listOf("String", "String")
        shape.modelImports shouldBe listOf("PetCreated")
    }

    test("list-payload channel — Reference.Iterable") {
        val channel = Channel(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("BatchChannel"),
            reference = Reference.Iterable(Reference.Custom("Item", false), false),
        )

        val shape = ChannelShape.from(channel)

        shape.payloadType shouldBe "List<Item>"
        shape.payloadFields shouldBe emptyList()
        shape.modelImports shouldBe listOf("Item")
    }
})
