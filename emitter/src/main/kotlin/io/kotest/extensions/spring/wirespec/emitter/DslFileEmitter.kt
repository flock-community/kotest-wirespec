package io.kotest.extensions.spring.wirespec.emitter

import community.flock.wirespec.compiler.core.emit.Emitted
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import community.flock.wirespec.ir.core.file
import community.flock.wirespec.ir.generator.KotlinGenerator

object DslFileEmitter {

    fun emit(endpoint: Endpoint, packageName: PackageName): Emitted {
        val shape = EndpointShape.from(endpoint)
        val kotestPkg = "${packageName.value}.kotest"
        val endpointPkg = "${packageName.value}.endpoint"
        val modelPkg = "${packageName.value}.model"
        val filePath = kotestPkg.replace('.', '/') + "/${shape.name}Dsl.kt"

        val irFile = file("${shape.name}Dsl") {
            `package`(kotestPkg)

            import("io.kotest.extensions.spring.wirespec.dsl", "ResultRef")
            import("io.kotest.extensions.spring.wirespec.dsl", "ScenarioBuilder")
            import("io.kotest.extensions.spring.wirespec.dsl", "WirespecScenarioDsl")
            import("io.kotest.extensions.spring.wirespec.dsl", "EndpointCallBuilder.StreamingMode")
            import("kotlin.time", "Duration")
            import(endpointPkg, shape.name)
            if (shape.bodyType != null) {
                import("community.flock.wirespec.integration.kotest", "KotestWirespecGeneratorBuilder")
                import("io.kotest.property", "Arb")
            }
            shape.modelImports.forEach { import(modelPkg, it) }

            raw(renderExtensionFunction(shape))
            raw(renderCallClass(shape))
        }

        return Emitted(file = filePath, result = KotlinGenerator.generate(irFile))
    }

    private fun renderExtensionFunction(shape: EndpointShape): String =
        "public val ScenarioBuilder.${shape.dslName}: ${shape.name}Call\n" +
            "    get() = ${shape.name}Call(this)"

    private fun renderCallClass(shape: EndpointShape): String = buildString {
        appendLine("@WirespecScenarioDsl")
        appendLine("public class ${shape.name}Call internal constructor(scenario: ScenarioBuilder) {")
        appendLine("    @PublishedApi internal val inner = scenario.endpoint(${shape.name}.Handler, ${shape.name})")
        if (shape.bodyType != null) append(renderBodySlot(shape, shape.bodyType))
        if (shape.pathFields.isNotEmpty()) append(renderPathSlot(shape))
        if (shape.queryFields.isNotEmpty()) append(renderQuerySlot(shape))
        if (shape.headerFields.isNotEmpty()) append(renderHeaderSlot(shape))
        append(renderResponseDsl(shape))
        append("\n}")
    }

    private fun renderHeaderSlot(shape: EndpointShape): String = buildString {
        val call = "${shape.name}Call"
        val params = shape.headerFields.joinToString(", ") { "${it.name}: ${it.kotlinType}" }
        val ctorArgs = shape.headerFields.joinToString(", ") { "${it.name} = ${it.name}" }

        appendLine("    public fun header($params): $call =")
        appendLine("        apply { inner.header(${shape.name}.RequestHeaders($ctorArgs)) }")
        appendLine("    public fun header(builder: () -> ${shape.name}.RequestHeaders): $call =")
        appendLine("        apply { inner.header(builder) }")
    }

    private fun renderQuerySlot(shape: EndpointShape): String = buildString {
        val call = "${shape.name}Call"
        val params = shape.queryFields.joinToString(", ") { "${it.name}: ${it.kotlinType}" }
        val ctorArgs = shape.queryFields.joinToString(", ") { "${it.name} = ${it.name}" }

        appendLine("    public fun query($params): $call =")
        appendLine("        apply { inner.query(${shape.name}.Queries($ctorArgs)) }")
        appendLine("    public fun query(builder: () -> ${shape.name}.Queries): $call =")
        appendLine("        apply { inner.query(builder) }")
    }

    private fun renderPathSlot(shape: EndpointShape): String = buildString {
        val call = "${shape.name}Call"
        val params = shape.pathFields.joinToString(", ") { "${it.name}: ${it.kotlinType}" }
        val ctorArgs = shape.pathFields.joinToString(", ") { "${it.name} = ${it.name}" }

        appendLine("    public fun path($params): $call =")
        appendLine("        apply { inner.path(${shape.name}.Path($ctorArgs)) }")

        if (shape.pathFields.size == 1) {
            val f = shape.pathFields.single()
            appendLine("    public fun path(${f.name}: ResultRef<${f.kotlinType}>): $call =")
            appendLine("        apply { inner.path { ${shape.name}.Path(${f.name} = ${f.name}.require()) } }")
        }

        appendLine("    public fun path(builder: () -> ${shape.name}.Path): $call =")
        appendLine("        apply { inner.path(builder) }")
    }

    private fun renderBodySlot(shape: EndpointShape, bodyType: String): String = buildString {
        val call = "${shape.name}Call"
        appendLine("    public fun body(value: $bodyType): $call =")
        appendLine("        apply { inner.body(value) }")
        appendLine("    public fun body(arb: Arb<$bodyType>): $call =")
        appendLine("        apply { inner.body(arb) }")
        appendLine("    public fun body(overrides: KotestWirespecGeneratorBuilder.() -> Unit): $call =")
        appendLine("        apply { inner.body(overrides) }")
    }

    private fun renderResponseDsl(shape: EndpointShape): String = buildString {
        val resp = "${shape.name}.Response<*>"
        val call = "${shape.name}Call"
        appendLine("    public inline fun <reified R : $resp> expecting(): $call =")
        appendLine("        apply { inner.expecting<R>() }")
        appendLine("    public inline fun <reified R : $resp> expecting(noinline block: (R) -> Unit): $call =")
        appendLine("        apply { inner.expecting<R>(block) }")
        appendLine("    public inline fun <reified R : $resp, T> returning(noinline projection: (R) -> T): ResultRef<T> =")
        appendLine("        inner.returning<R, T>(projection)")
        appendLine("    public inline fun <reified R : $resp> collecting(count: Int, noinline block: (List<R>) -> Unit): $call =")
        appendLine("        apply { inner.collecting<R>(count, block) }")
        appendLine("    public inline fun <reified R : $resp> collecting(duration: Duration, noinline block: (List<R>) -> Unit): $call =")
        append("        apply { inner.collecting<R>(duration, block) }")
    }
}
