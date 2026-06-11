package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive.Type
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KotlinTypeMapperTest : FunSpec({
    val notNull = false
    val nullable = true

    test("String primitive") {
        KotlinTypeMapper.map(Reference.Primitive(Type.String(null), notNull)) shouldBe "String"
        KotlinTypeMapper.map(Reference.Primitive(Type.String(null), nullable)) shouldBe "String?"
    }
    test("Integer precision -> Int / Long") {
        KotlinTypeMapper.map(Reference.Primitive(Type.Integer(Type.Precision.P32, null), notNull)) shouldBe "Int"
        KotlinTypeMapper.map(Reference.Primitive(Type.Integer(Type.Precision.P64, null), notNull)) shouldBe "Long"
    }
    test("Number precision -> Float / Double") {
        KotlinTypeMapper.map(Reference.Primitive(Type.Number(Type.Precision.P32, null), notNull)) shouldBe "Float"
        KotlinTypeMapper.map(Reference.Primitive(Type.Number(Type.Precision.P64, null), notNull)) shouldBe "Double"
    }
    test("Boolean / Bytes") {
        KotlinTypeMapper.map(Reference.Primitive(Type.Boolean, notNull)) shouldBe "Boolean"
        KotlinTypeMapper.map(Reference.Primitive(Type.Bytes, notNull)) shouldBe "ByteArray"
    }
    test("Custom") {
        KotlinTypeMapper.map(Reference.Custom("Pet", notNull)) shouldBe "Pet"
        KotlinTypeMapper.map(Reference.Custom("Pet", nullable)) shouldBe "Pet?"
    }
    test("Iterable -> List") {
        val pet = Reference.Custom("Pet", notNull)
        KotlinTypeMapper.map(Reference.Iterable(pet, notNull)) shouldBe "List<Pet>"
    }
    test("Dict -> Map<String, X>") {
        val pet = Reference.Custom("Pet", notNull)
        KotlinTypeMapper.map(Reference.Dict(pet, notNull)) shouldBe "Map<String, Pet>"
    }
    test("Unit / Any") {
        KotlinTypeMapper.map(Reference.Unit(notNull)) shouldBe "Unit"
        KotlinTypeMapper.map(Reference.Any(notNull)) shouldBe "Any"
    }
})
