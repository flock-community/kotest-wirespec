package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.emit.Emitted
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import community.flock.wirespec.compiler.core.parse.ast.Refined
import community.flock.wirespec.compiler.core.parse.ast.Type
import community.flock.wirespec.ir.core.file
import community.flock.wirespec.ir.generator.KotlinGenerator

object DslFileEmitter {

    fun emit(
        endpoint: Endpoint,
        packageName: PackageName,
        types: Map<String, Type> = emptyMap(),
        refined: Map<String, Refined> = emptyMap(),
    ): Emitted {
        val shape = EndpointShape.from(endpoint, types, refined)
        val kotestPkg = "${packageName.value}.kotest"
        val endpointPkg = "${packageName.value}.endpoint"
        val modelPkg = "${packageName.value}.model"
        val filePath = kotestPkg.replace('.', '/') + "/${shape.name}Dsl.kt"

        val irFile = file("${shape.name}Dsl") {
            `package`(kotestPkg)

            import("io.kotest.extensions.wirespec.dsl", "ResultRef")
            import("io.kotest.extensions.wirespec.dsl", "ScenarioBuilder")
            import("io.kotest.extensions.wirespec.dsl", "WirespecScenarioDsl")
            import("io.kotest.extensions.wirespec.dsl", "EndpointCallBuilder.StreamingMode")
            import("kotlin.time", "Duration")
            import(endpointPkg, shape.name)
            if (shape.bodyType != null) {
                import("io.kotest.property", "Arb")
            }
            if (shape.bodyType != null && hasPrimitiveField(shape.bodyFieldShapes)) {
                import("io.kotest.extensions.wirespec.dsl", "asArb")
                import("io.kotest.property", "Gen")
            }
            if (shape.bodyKind == EndpointShape.BodyKind.List) {
                // Arb.int is an extension on Arb.Companion in io.kotest.property.arbitrary
                // and must be imported explicitly when referenced as `Arb.int(count)`.
                import("io.kotest.property.arbitrary", "int")
            }
            shape.modelImports.forEach { import(modelPkg, it) }

            raw(renderCallClass(shape))
            if (shape.bodyType != null && shape.bodyFieldShapes.isNotEmpty()) {
                val element = shape.bodyElementType
                    ?: error("bodyFieldShapes present but no bodyElementType for ${shape.name}")
                raw(renderBodyBuilder(element, shape.bodyFieldShapes, shape.name))
                // Emit nested-type builders, deduped by type name. Each appears once per file.
                val nestedDefs = collectNestedBuilders(shape.bodyFieldShapes, alreadyEmitted = setOf(element))
                nestedDefs.forEach { (typeName, fields) ->
                    raw(renderBodyBuilder(typeName, fields, shape.name))
                }
            }
        }

        return Emitted(file = filePath, result = KotlinGenerator.generate(irFile))
    }

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
        if (shape.bodyFieldShapes.isNotEmpty()) {
            val element = shape.bodyElementType ?: error("bodyFieldShapes present but no bodyElementType")
            val builderName = "${shape.name}${element}BodyBuilder"
            val rootPrefix = if (shape.bodyKind == EndpointShape.BodyKind.List) listOf("\"*\"") else emptyList()
            val isList = shape.bodyKind == EndpointShape.BodyKind.List
            val signature = if (isList) {
                "body(count: IntRange = 1..3, block: $builderName.() -> Unit)"
            } else {
                "body(block: $builderName.() -> Unit)"
            }
            appendLine("    public fun $signature: $call = apply {")
            appendLine("        val builder = $builderName().apply(block)")
            if (isList) {
                appendLine("        inner.bodyListSize(Arb.int(count))")
            }
            appendLine("        inner.body {")
            renderFieldRegistrations(this, "builder", shape.bodyFieldShapes, rootPrefix, indent = "            ", builderPrefix = shape.name)
            appendLine("        }")
            appendLine("    }")
        }
    }

    private fun renderFieldRegistrations(
        out: StringBuilder,
        receiver: String,
        fields: List<EndpointShape.BodyFieldShape>,
        pathPrefix: List<String>,
        indent: String,
        builderPrefix: String,
    ) {
        fields.forEach { f ->
            val nameSegment = "\"${f.name}\""
            val pathArgs = (pathPrefix + nameSegment).joinToString(", ")
            when (f) {
                is EndpointShape.BodyFieldShape.Primitive -> {
                    out.appendLine("$indent$receiver.${f.name}?.let { registerPath($pathArgs) { it.asArb() } }")
                }
                is EndpointShape.BodyFieldShape.NestedObject -> {
                    val nestedBuilder = "$builderPrefix${f.typeName}BodyBuilder"
                    val nestedVar = "nested_${f.name}"
                    out.appendLine("$indent$receiver._${f.name}Block?.let { block ->")
                    out.appendLine("$indent    val $nestedVar = $nestedBuilder().apply(block)")
                    renderFieldRegistrations(out, nestedVar, f.fields, pathPrefix + nameSegment, "$indent    ", builderPrefix)
                    out.appendLine("$indent}")
                }
                is EndpointShape.BodyFieldShape.NestedList -> {
                    val nestedBuilder = "$builderPrefix${f.elementTypeName}BodyBuilder"
                    val nestedVar = "nested_${f.name}"
                    out.appendLine("$indent$receiver._${f.name}Block?.let { block ->")
                    out.appendLine("$indent    val $nestedVar = $nestedBuilder().apply(block)")
                    renderFieldRegistrations(out, nestedVar, f.fields, pathPrefix + nameSegment + "\"*\"", "$indent    ", builderPrefix)
                    out.appendLine("$indent}")
                }
            }
        }
    }

    private fun renderBodyBuilder(
        elementType: String,
        fields: List<EndpointShape.BodyFieldShape>,
        builderPrefix: String,
    ): String = buildString {
        appendLine("@WirespecScenarioDsl")
        appendLine("public class $builderPrefix${elementType}BodyBuilder {")
        fields.forEach { f ->
            when (f) {
                is EndpointShape.BodyFieldShape.Primitive -> {
                    appendLine("    public var ${f.name}: Gen<${f.kotlinType}>? = null")
                }
                is EndpointShape.BodyFieldShape.NestedObject -> {
                    appendLine("    @PublishedApi internal var _${f.name}Block: ($builderPrefix${f.typeName}BodyBuilder.() -> Unit)? = null")
                    appendLine("    public fun ${f.name}(block: $builderPrefix${f.typeName}BodyBuilder.() -> Unit) { _${f.name}Block = block }")
                }
                is EndpointShape.BodyFieldShape.NestedList -> {
                    appendLine("    @PublishedApi internal var _${f.name}Block: ($builderPrefix${f.elementTypeName}BodyBuilder.() -> Unit)? = null")
                    appendLine("    public fun ${f.name}(block: $builderPrefix${f.elementTypeName}BodyBuilder.() -> Unit) { _${f.name}Block = block }")
                }
            }
        }
        append("}")
    }

    private fun hasPrimitiveField(fields: List<EndpointShape.BodyFieldShape>): Boolean =
        fields.any { f ->
            when (f) {
                is EndpointShape.BodyFieldShape.Primitive -> true
                is EndpointShape.BodyFieldShape.NestedObject -> hasPrimitiveField(f.fields)
                is EndpointShape.BodyFieldShape.NestedList -> hasPrimitiveField(f.fields)
            }
        }

    private fun collectNestedBuilders(
        fields: List<EndpointShape.BodyFieldShape>,
        alreadyEmitted: Set<String>,
    ): List<Pair<String, List<EndpointShape.BodyFieldShape>>> {
        val result = mutableListOf<Pair<String, List<EndpointShape.BodyFieldShape>>>()
        val emitted = alreadyEmitted.toMutableSet()
        fun walk(fs: List<EndpointShape.BodyFieldShape>) {
            fs.forEach { f ->
                when (f) {
                    is EndpointShape.BodyFieldShape.Primitive -> Unit
                    is EndpointShape.BodyFieldShape.NestedObject -> {
                        if (emitted.add(f.typeName)) { result += f.typeName to f.fields; walk(f.fields) }
                    }
                    is EndpointShape.BodyFieldShape.NestedList -> {
                        if (emitted.add(f.elementTypeName)) { result += f.elementTypeName to f.fields; walk(f.fields) }
                    }
                }
            }
        }
        walk(fields)
        return result
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
