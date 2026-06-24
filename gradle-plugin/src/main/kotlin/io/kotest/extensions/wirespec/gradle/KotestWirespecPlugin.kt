package io.kotest.extensions.wirespec.gradle

import community.flock.wirespec.emitters.kotlin.KotlinIrEmitter
import community.flock.wirespec.integration.kotest.extension.KotestDslExtension
import community.flock.wirespec.plugin.gradle.CompileWirespecTask
import community.flock.wirespec.spring.extractor.gradle.ExtractWirespecTask
import community.flock.wirespec.spring.extractor.gradle.WirespecExtractorExtension
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPluginExtension

/**
 * Package the wirespec `KotlinIrEmitter` (0.20.0-RC.2) emits generated models into
 * unconditionally. It ignores the task's `packageName` for models, so the generated
 * Kotest DSL (which does honor `packageName`) is pinned to this same package to keep
 * the two compilable together. See the usage site for the full explanation.
 */
private const val WIRESPEC_IR_GENERATED_PACKAGE = "community.flock.wirespec.generated"

class KotestWirespecPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "kotestWirespec",
            KotestWirespecExtension::class.java,
        )

        extension.spring.convention(
            project.provider { project.plugins.hasPlugin("org.springframework.boot") }
        )

        project.pluginManager.apply("community.flock.wirespec.plugin.gradle")

        val extractedDir = project.layout.buildDirectory.dir("wirespec")
        val generatedDir = project.layout.buildDirectory.dir("generated/wirespec")
        val defaultInputDir = project.layout.projectDirectory.dir("src/test/wirespec")

        val compileTask = project.tasks.register(
            "wirespecKotlin",
            CompileWirespecTask::class.java,
            object : Action<CompileWirespecTask> {
                override fun execute(task: CompileWirespecTask) {
                    task.description = "Generate Kotlin sources + Kotest scenario DSL from the extracted Wirespec contracts."
                    task.group = "wirespec"
                    // wirespecPath, when set, always wins. Otherwise this
                    // default applies when spring=false; the afterEvaluate
                    // block below overrides it to the extracted dir when
                    // spring=true and wirespecPath is unset.
                    task.input.set(extension.wirespecPath.orElse(defaultInputDir))
                    task.output.set(generatedDir)
                    // RC limitation: wirespec 0.20.0-RC.2's `KotlinIrEmitter` hardcodes
                    // generated models to `community.flock.wirespec.generated`, ignoring
                    // `packageName` (only the Kotest DSL honors it). Pinning packageName to
                    // that same value keeps the generated DSL and models in one package so
                    // they compile together. The `generatedPackage`/`basePackage` namespace
                    // for generated code is therefore inert until a wirespec release restores
                    // packageName handling in the IR emitter; `basePackage` is still used to
                    // drive the Spring extractor below.
                    task.packageName.set(WIRESPEC_IR_GENERATED_PACKAGE)
                    // Emit Kotlin models with the IR emitter and layer wirespec's own
                    // Kotest scenario DSL (`<Endpoint>.call { }`) via KotestDslExtension.
                    task.emitterClass.set(KotlinIrEmitter::class.java)
                    task.extensionClasses.set(listOf(KotestDslExtension::class.java))
                    // The shared `Wirespec` runtime comes from `wirespec-jvm` (pulled in
                    // transitively by the kotest integration), so don't emit a copy.
                    task.shared.set(false)
                }
            },
        )

        // Wire the Spring extractor only when requested. Lookup happens at
        // task-graph configuration time via afterEvaluate so the user can set
        // `spring = false` in their build script regardless of plugin order.
        project.afterEvaluate {
            if (extension.spring.get()) {
                project.pluginManager.apply("community.flock.wirespec.spring.extractor")
                val extractorExt = project.extensions.getByType(WirespecExtractorExtension::class.java)
                // wirespecPath, when set, is both where the extractor writes
                // and where the compile task reads; otherwise the build dir.
                extractorExt.outputDir.set(extension.wirespecPath.orElse(extractedDir))
                extractorExt.basePackage.set(extension.basePackage)

                val extractTask = project.tasks.named("extractWirespec", ExtractWirespecTask::class.java)
                compileTask.configure(
                    object : Action<CompileWirespecTask> {
                        override fun execute(task: CompileWirespecTask) {
                            task.input.set(extension.wirespecPath.orElse(extractedDir))
                            task.dependsOn(extractTask)
                        }
                    },
                )
            }
        }

        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            val sourceSets = project.extensions.getByType(JavaPluginExtension::class.java).sourceSets
            sourceSets.getByName("test").java.srcDir(compileTask.flatMap { it.output })
            project.tasks.named("compileTestKotlin").configure(
                object : Action<Task> {
                    override fun execute(task: Task) {
                        task.dependsOn(compileTask)
                    }
                },
            )
        }
    }
}
