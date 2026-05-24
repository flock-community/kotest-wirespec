package io.kotest.extensions.spring.wirespec.emitter

import community.flock.wirespec.compiler.core.emit.Emitted
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.Type
import community.flock.wirespec.ir.core.file
import community.flock.wirespec.ir.generator.KotlinGenerator

object ChannelDslFileEmitter {

    fun emit(
        channel: Channel,
        packageName: PackageName,
        types: Map<String, Type> = emptyMap(),
    ): Emitted {
        val shape = ChannelShape.from(channel, types)
        val kotestPkg = "${packageName.value}.kotest"
        val channelPkg = "${packageName.value}.channel"
        val modelPkg = "${packageName.value}.model"
        val filePath = kotestPkg.replace('.', '/') + "/${shape.name}Dsl.kt"

        val irFile = file("${shape.name}Dsl") {
            `package`(kotestPkg)

            import("io.kotest.extensions.spring.wirespec.dsl", "ResultRef")
            import("io.kotest.extensions.spring.wirespec.dsl", "ScenarioBuilder")
            import("io.kotest.extensions.spring.wirespec.dsl", "WirespecScenarioDsl")
            import("io.kotest.property", "Arb")
            import("kotlin.time", "Duration")
            import(channelPkg, shape.name)
            shape.modelImports.forEach { import(modelPkg, it) }

            raw(renderExtensionFunction(shape))
            raw(renderCallClass(shape))
            if (shape.payloadFields.isNotEmpty()) {
                raw(renderPayloadBuilder(shape.payloadType, shape.payloadFields))
            }
        }

        return Emitted(file = filePath, result = KotlinGenerator.generate(irFile))
    }

    private fun renderExtensionFunction(shape: ChannelShape): String =
        "public val ScenarioBuilder.${shape.dslName}: ${shape.name}Call\n" +
            "    get() = ${shape.name}Call(this)"

    private fun renderCallClass(shape: ChannelShape): String = buildString {
        val call = "${shape.name}Call"
        val payload = shape.payloadType
        appendLine("@WirespecScenarioDsl")
        appendLine("public class $call internal constructor(scenario: ScenarioBuilder) {")
        appendLine("    @PublishedApi internal val inner = scenario.channel<$payload>(${shape.name}::class)")
        appendLine("    public fun topic(value: String): $call =")
        appendLine("        apply { inner.topic(value) }")
        appendLine("    public fun topic(ref: ResultRef<String>): $call =")
        appendLine("        apply { inner.topic { ref.require() } }")
        appendLine("    public fun key(value: String): $call =")
        appendLine("        apply { inner.key(value) }")
        appendLine("    public fun send(value: $payload): $call =")
        appendLine("        apply { inner.send(value) }")
        appendLine("    public fun send(arb: Arb<$payload>): $call =")
        appendLine("        apply { inner.send(arb) }")
        if (shape.payloadFields.isNotEmpty()) {
            appendLine("    public fun send(block: ${payload}PayloadBuilder.() -> Unit): $call = apply {")
            appendLine("        val builder = ${payload}PayloadBuilder().apply(block)")
            appendLine("        inner.send {")
            shape.payloadFields.forEach { f ->
                appendLine("            builder.${f.name}?.let { registerPath(\"${f.name}\") { it } }")
            }
            appendLine("        }")
            appendLine("    }")
        }
        appendLine("    public fun expecting(): $call =")
        appendLine("        apply { inner.expecting() }")
        appendLine("    public fun expecting(block: ($payload) -> Unit): $call =")
        appendLine("        apply { inner.expecting(block) }")
        appendLine("    public fun collecting(count: Int, block: (List<$payload>) -> Unit): $call =")
        appendLine("        apply { inner.collecting(count, block) }")
        appendLine("    public fun collecting(duration: Duration, block: (List<$payload>) -> Unit): $call =")
        appendLine("        apply { inner.collecting(duration, block) }")
        appendLine("    public fun <T> returning(projection: ($payload) -> T): ResultRef<T> =")
        append("        inner.returning(projection)\n}")
    }

    private fun renderPayloadBuilder(payloadType: String, fields: List<EndpointShape.NamedTypedField>): String = buildString {
        appendLine("@WirespecScenarioDsl")
        appendLine("public class ${payloadType}PayloadBuilder {")
        fields.forEach { f ->
            appendLine("    public var ${f.name}: Arb<${f.kotlinType}>? = null")
        }
        append("}")
    }
}
