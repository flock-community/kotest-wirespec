package io.kotest.extensions.spring.wirespec.emitter

import community.flock.wirespec.compiler.core.emit.Emitted
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.Endpoint

object DslFileEmitter {

    fun emit(endpoint: Endpoint, packageName: PackageName): Emitted {
        val shape = EndpointShape.from(endpoint)
        val endpointPkg = "${packageName.value}.endpoint"
        val file = endpointPkg.replace('.', '/') + "/${shape.name}Dsl.kt"
        return Emitted(file = file, result = render(shape, endpointPkg))
    }

    private fun render(shape: EndpointShape, endpointPkg: String): String = buildString {
        appendLine("package $endpointPkg")
        appendLine()
        appendLine("import io.kotest.extensions.spring.wirespec.dsl.ResultRef")
        appendLine("import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder")
        appendLine("import io.kotest.extensions.spring.wirespec.dsl.WirespecScenarioDsl")
        appendLine("import io.kotest.extensions.spring.wirespec.dsl.EndpointCallBuilder.StreamingMode")
        appendLine("import kotlin.time.Duration")
        appendLine()
        appendLine("public fun ScenarioBuilder.${shape.dslName}(block: ${shape.name}Call.() -> Unit = {}): ${shape.name}Call =")
        appendLine("    ${shape.name}Call(this).apply(block)")
        appendLine()
        appendLine("@WirespecScenarioDsl")
        appendLine("public class ${shape.name}Call internal constructor(scenario: ScenarioBuilder) {")
        appendLine("    @PublishedApi internal val inner = scenario.endpoint(${shape.name}.Handler)")
        appendLine()
        renderResponseDsl(shape)
        appendLine("}")
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
