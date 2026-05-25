package io.kotest.extensions.wirespec.gradle

import community.flock.wirespec.plugin.gradle.CompileWirespecTask
import community.flock.wirespec.spring.extractor.gradle.ExtractWirespecTask
import community.flock.wirespec.spring.extractor.gradle.WirespecExtractorExtension
import io.kotest.extensions.wirespec.emitter.TypesafeDslEmitter
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPluginExtension

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

        val extractedDir = project.layout.buildDirectory.dir("wirespec/extracted")
        val generatedDir = project.layout.buildDirectory.dir("generated/wirespec")
        val defaultInputDir = project.layout.projectDirectory.dir("src/test/wirespec")
        val resolvedGeneratedPackage = extension.generatedPackage
            .orElse(extension.basePackage.map { "$it.generated" })

        val compileTask = project.tasks.register(
            "wirespecKotlin",
            CompileWirespecTask::class.java,
            object : Action<CompileWirespecTask> {
                override fun execute(task: CompileWirespecTask) {
                    task.description = "Generate Kotlin sources + typesafe DSL from the extracted Wirespec contracts."
                    task.group = "wirespec"
                    // Default input is used when spring=false; afterEvaluate
                    // below overrides it to the extracted dir when spring=true.
                    task.input.set(defaultInputDir)
                    task.output.set(generatedDir)
                    task.packageName.set(resolvedGeneratedPackage)
                    task.emitterClass.set(TypesafeDslEmitter::class.java)
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
                extractorExt.outputDir.set(extractedDir)
                extractorExt.basePackage.set(extension.basePackage)

                val extractTask = project.tasks.named("extractWirespec", ExtractWirespecTask::class.java)
                compileTask.configure(
                    object : Action<CompileWirespecTask> {
                        override fun execute(task: CompileWirespecTask) {
                            task.input.set(extractedDir)
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
