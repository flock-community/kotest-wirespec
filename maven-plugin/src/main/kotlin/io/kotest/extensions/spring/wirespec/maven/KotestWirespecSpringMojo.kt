package io.kotest.extensions.spring.wirespec.maven

import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.BuildPluginManager
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import org.twdata.maven.mojoexecutor.MojoExecutor.artifactId
import org.twdata.maven.mojoexecutor.MojoExecutor.configuration
import org.twdata.maven.mojoexecutor.MojoExecutor.dependency
import org.twdata.maven.mojoexecutor.MojoExecutor.element
import org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo
import org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment
import org.twdata.maven.mojoexecutor.MojoExecutor.goal
import org.twdata.maven.mojoexecutor.MojoExecutor.groupId
import org.twdata.maven.mojoexecutor.MojoExecutor.plugin
import org.twdata.maven.mojoexecutor.MojoExecutor.version
import java.io.File

@Mojo(
    name = "generate",
    defaultPhase = LifecyclePhase.GENERATE_TEST_SOURCES,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true,
)
class KotestWirespecSpringMojo : AbstractMojo() {

    @Parameter(property = "kotestWirespecSpring.basePackage", required = true)
    lateinit var basePackage: String

    @Parameter(property = "kotestWirespecSpring.generatedPackage")
    var generatedPackage: String? = null

    @Parameter(defaultValue = "\${project.build.directory}/wirespec/extracted")
    lateinit var extractedDir: File

    @Parameter(defaultValue = "\${project.build.directory}/generated-sources/wirespec")
    lateinit var generatedDir: File

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    lateinit var project: MavenProject

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    lateinit var session: MavenSession

    @Component
    lateinit var pluginManager: BuildPluginManager

    override fun execute() {
        val env = executionEnvironment(project, session, pluginManager)
        val effectivePackage = generatedPackage?.takeIf { it.isNotBlank() }
            ?: "$basePackage.generated"

        log.info("Extracting Wirespec from package $basePackage")
        executeMojo(
            plugin(
                groupId(EXTRACTOR_GROUP),
                artifactId(EXTRACTOR_ARTIFACT),
                version(EXTRACTOR_VERSION),
            ),
            goal("extract"),
            configuration(
                element("basePackage", basePackage),
                element("output", extractedDir.absolutePath),
            ),
            env,
        )

        log.info("Generating typesafe Kotest DSL into $generatedDir (package $effectivePackage)")
        executeMojo(
            plugin(
                groupId(WIRESPEC_GROUP),
                artifactId(WIRESPEC_ARTIFACT),
                version(WIRESPEC_VERSION),
                listOf(
                    dependency(EMITTER_GROUP, EMITTER_ARTIFACT, EMITTER_VERSION),
                ),
            ),
            goal("compile"),
            configuration(
                element("input", extractedDir.absolutePath),
                element("output", generatedDir.absolutePath),
                element("packageName", effectivePackage),
                element("emitterClass", EMITTER_FQCN),
                element("languages", element("language", "Kotlin")),
            ),
            env,
        )

        project.addTestCompileSourceRoot(generatedDir.absolutePath)
    }

    private companion object {
        const val EXTRACTOR_GROUP = "community.flock.wirespec.spring"
        const val EXTRACTOR_ARTIFACT = "wirespec-spring-extractor-maven-plugin"
        const val EXTRACTOR_VERSION = "0.0.5"

        const val WIRESPEC_GROUP = "community.flock.wirespec.plugin.maven"
        const val WIRESPEC_ARTIFACT = "wirespec-maven-plugin"
        // Must match the wirespecVersion used by emitter/ — see gradle.properties.
        // Mismatched versions cause Arrow 1.x vs 2.x classloader incompatibility
        // when the upstream compiler and our emitter share a plugin realm.
        const val WIRESPEC_VERSION = "0.19.0-RC.3"

        const val EMITTER_GROUP = "io.kotest.extensions"
        const val EMITTER_ARTIFACT = "kotest-extensions-spring-wirespec-emitter"
        const val EMITTER_VERSION = "0.1.0-SNAPSHOT"
        const val EMITTER_FQCN =
            "io.kotest.extensions.spring.wirespec.emitter.TypesafeDslEmitter"
    }
}
