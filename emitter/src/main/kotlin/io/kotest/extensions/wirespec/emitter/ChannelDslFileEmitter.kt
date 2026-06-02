package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.emit.Emitted
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.Refined
import community.flock.wirespec.compiler.core.parse.ast.Type
import community.flock.wirespec.ir.core.file
import community.flock.wirespec.ir.generator.KotlinGenerator

object ChannelDslFileEmitter {

    fun emit(
        channel: Channel,
        packageName: PackageName,
        types: Map<String, Type> = emptyMap(),
        refined: Map<String, Refined> = emptyMap(),
    ): Emitted {
        val shape = ChannelShape.from(channel, types, refined)
        val kotestPkg = "${packageName.value}.kotest"
        val channelPkg = "${packageName.value}.channel"
        val modelPkg = "${packageName.value}.model"
        val filePath = kotestPkg.replace('.', '/') + "/${shape.name}Dsl.kt"

        val irFile = file("${shape.name}Dsl") {
            `package`(kotestPkg)

            import("io.kotest.extensions.wirespec.dsl", "ResultRef")
            import("io.kotest.extensions.wirespec.dsl", "ScenarioBuilder")
            import("io.kotest.extensions.wirespec.dsl", "WirespecScenarioDsl")
            import("io.kotest.property", "Arb")
            if (shape.payloadFields.isNotEmpty()) {
                import("io.kotest.extensions.wirespec.dsl", "asArb")
                import("io.kotest.property", "Gen")
            }
            import("kotlin.time", "Duration")
            import(channelPkg, shape.name)
            shape.modelImports.forEach { import(modelPkg, it) }

            raw(renderCallClass(shape))
            if (shape.payloadFields.isNotEmpty()) {
                raw(renderPayloadBuilder(shape.payloadType, shape.payloadFields))
            }
        }

        return Emitted(file = filePath, result = KotlinGenerator.generate(irFile))
    }

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
        appendLine("    public fun send(): $call =")
        appendLine("        apply { inner.send() }")
        appendLine("    public fun send(value: $payload): $call =")
        appendLine("        apply { inner.send(value) }")
        appendLine("    public fun send(arb: Arb<$payload>): $call =")
        appendLine("        apply { inner.send(arb) }")
        if (shape.payloadFields.isNotEmpty()) {
            appendLine("    public fun send(block: ${payload}PayloadBuilder.() -> Unit): $call = apply {")
            appendLine("        val builder = ${payload}PayloadBuilder().apply(block)")
            appendLine("        inner.send {")
            shape.payloadFields.forEach { f ->
                appendLine("            builder.${f.name}?.let { registerPath(\"${f.name}\") { it.asArb() } }")
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
            appendLine("    public var ${f.name}: Gen<${f.kotlinType}>? = null")
        }
        append("}")
    }
}
