package io.kotest.extensions.spring.wirespec.emitter

import community.flock.wirespec.compiler.core.emit.Emitted
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.Endpoint

object DslFileEmitter {

    fun emit(endpoint: Endpoint, packageName: PackageName): Emitted {
        val shape = EndpointShape.from(endpoint)
        val endpointPkg = "${packageName.value}.endpoint"
        val modelPkg = "${packageName.value}.model"
        val file = endpointPkg.replace('.', '/') + "/${shape.name}Dsl.kt"
        return Emitted(file = file, result = render(shape, endpointPkg, modelPkg))
    }

    private fun render(shape: EndpointShape, endpointPkg: String, modelPkg: String): String = buildString {
        appendLine("package $endpointPkg")
        appendLine()
        appendLine("import io.kotest.extensions.spring.wirespec.dsl.ResultRef")
        appendLine("import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder")
        appendLine("import io.kotest.extensions.spring.wirespec.dsl.WirespecScenarioDsl")
        appendLine("import io.kotest.extensions.spring.wirespec.dsl.EndpointCallBuilder.StreamingMode")
        appendLine("import kotlin.time.Duration")
        if (shape.bodyType != null) {
            appendLine("import community.flock.wirespec.integration.kotest.KotestWirespecGeneratorBuilder")
            appendLine("import io.kotest.property.Arb")
        }
        shape.modelImports.forEach { appendLine("import $modelPkg.$it") }
        appendLine()
        appendLine("public fun ScenarioBuilder.${shape.dslName}(block: ${shape.name}Call.() -> Unit = {}): ${shape.name}Call =")
        appendLine("    ${shape.name}Call(this).apply(block)")
        appendLine()
        appendLine("@WirespecScenarioDsl")
        appendLine("public class ${shape.name}Call internal constructor(scenario: ScenarioBuilder) {")
        appendLine("    @PublishedApi internal val inner = scenario.endpoint(${shape.name}.Handler)")
        appendLine()
        if (shape.bodyType != null) renderBodySlot(shape, shape.bodyType)
        if (shape.pathFields.isNotEmpty()) renderPathSlot(shape)
        if (shape.queryFields.isNotEmpty()) renderQuerySlot(shape)
        if (shape.headerFields.isNotEmpty()) renderHeaderSlot(shape)
        renderResponseDsl(shape)
        appendLine("}")
    }

    private fun StringBuilder.renderHeaderSlot(shape: EndpointShape) {
        val call = "${shape.name}Call"
        val params = shape.headerFields.joinToString(", ") { "${it.name}: ${it.kotlinType}" }
        val ctorArgs = shape.headerFields.joinToString(", ") { "${it.name} = ${it.name}" }

        appendLine("    public fun header($params): $call =")
        appendLine("        apply { inner.header(${shape.name}.RequestHeaders($ctorArgs)) }")
        appendLine()
        appendLine("    public fun header(builder: () -> ${shape.name}.RequestHeaders): $call =")
        appendLine("        apply { inner.header(builder) }")
        appendLine()
    }

    private fun StringBuilder.renderQuerySlot(shape: EndpointShape) {
        val call = "${shape.name}Call"
        val params = shape.queryFields.joinToString(", ") { "${it.name}: ${it.kotlinType}" }
        val ctorArgs = shape.queryFields.joinToString(", ") { "${it.name} = ${it.name}" }

        appendLine("    public fun query($params): $call =")
        appendLine("        apply { inner.query(${shape.name}.Queries($ctorArgs)) }")
        appendLine()
        appendLine("    public fun query(builder: () -> ${shape.name}.Queries): $call =")
        appendLine("        apply { inner.query(builder) }")
        appendLine()
    }

    private fun StringBuilder.renderPathSlot(shape: EndpointShape) {
        val call = "${shape.name}Call"
        val params = shape.pathFields.joinToString(", ") { "${it.name}: ${it.kotlinType}" }
        val ctorArgs = shape.pathFields.joinToString(", ") { "${it.name} = ${it.name}" }

        appendLine("    public fun path($params): $call =")
        appendLine("        apply { inner.path(${shape.name}.Path($ctorArgs)) }")
        appendLine()

        if (shape.pathFields.size == 1) {
            val f = shape.pathFields.single()
            appendLine("    public fun path(${f.name}: ResultRef<${f.kotlinType}>): $call =")
            appendLine("        apply { inner.path { ${shape.name}.Path(${f.name} = ${f.name}.require()) } }")
            appendLine()
        }

        appendLine("    public fun path(builder: () -> ${shape.name}.Path): $call =")
        appendLine("        apply { inner.path(builder) }")
        appendLine()
    }

    private fun StringBuilder.renderBodySlot(shape: EndpointShape, bodyType: String) {
        val call = "${shape.name}Call"
        appendLine("    public fun body(value: $bodyType): $call =")
        appendLine("        apply { inner.body(value) }")
        appendLine()
        appendLine("    public fun body(arb: Arb<$bodyType>): $call =")
        appendLine("        apply { inner.body(arb) }")
        appendLine()
        appendLine("    public fun body(overrides: KotestWirespecGeneratorBuilder.() -> Unit): $call =")
        appendLine("        apply { inner.body(overrides) }")
        appendLine()
    }

    private fun StringBuilder.renderResponseDsl(shape: EndpointShape) {
        val resp = "${shape.name}.Response<*>"
        val call = "${shape.name}Call"
        appendLine("    public inline fun <reified R : $resp> expecting(): $call =")
        appendLine("        apply { inner.expecting<R>() }")
        appendLine()
        appendLine("    public inline fun <reified R : $resp> expecting(noinline block: (R) -> Unit): $call =")
        appendLine("        apply { inner.expecting<R>(block) }")
        appendLine()
        appendLine("    public inline fun <reified R : $resp, T> returning(noinline projection: (R) -> T): ResultRef<T> =")
        appendLine("        inner.returning<R, T>(projection)")
        appendLine()
        appendLine("    public inline fun <reified R : $resp> collecting(count: Int, noinline block: (List<R>) -> Unit): $call =")
        appendLine("        apply { inner.collecting<R>(count, block) }")
        appendLine()
        appendLine("    public inline fun <reified R : $resp> collecting(duration: Duration, noinline block: (List<R>) -> Unit): $call =")
        appendLine("        apply { inner.collecting<R>(duration, block) }")
    }
}
