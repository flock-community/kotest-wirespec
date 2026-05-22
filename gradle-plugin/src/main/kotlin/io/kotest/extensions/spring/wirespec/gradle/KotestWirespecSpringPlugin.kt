package io.kotest.extensions.spring.wirespec.gradle

import community.flock.wirespec.plugin.gradle.CompileWirespecTask
import community.flock.wirespec.spring.extractor.gradle.ExtractWirespecTask
import community.flock.wirespec.spring.extractor.gradle.WirespecExtractorExtension
import io.kotest.extensions.spring.wirespec.emitter.TypesafeDslEmitter
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPluginExtension

class KotestWirespecSpringPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "kotestWirespecSpring",
            KotestWirespecSpringExtension::class.java,
        )

        project.pluginManager.apply("community.flock.wirespec.plugin.gradle")
        project.pluginManager.apply("community.flock.wirespec.spring.extractor")

        val extractedDir = project.layout.buildDirectory.dir("wirespec/extracted")
        val extractorExt = project.extensions.getByType(WirespecExtractorExtension::class.java)
        extractorExt.outputDir.set(extractedDir)
        extractorExt.basePackage.set(extension.basePackage)

        val generatedDir = project.layout.buildDirectory.dir("generated/wirespec")
        val resolvedGeneratedPackage = extension.generatedPackage
            .orElse(extension.basePackage.map { "$it.generated" })

        val extractTask = project.tasks.named("extractWirespec", ExtractWirespecTask::class.java)

        val compileTask = project.tasks.register(
            "wirespecKotlin",
            CompileWirespecTask::class.java,
            object : Action<CompileWirespecTask> {
                override fun execute(task: CompileWirespecTask) {
                    task.description = "Generate Kotlin sources + typesafe DSL from the extracted Wirespec contracts."
                    task.group = "wirespec"
                    task.input.set(extractedDir)
                    task.output.set(generatedDir)
                    task.packageName.set(resolvedGeneratedPackage)
                    task.emitterClass.set(TypesafeDslEmitter::class.java)
                    task.dependsOn(extractTask)
                }
            },
        )

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
