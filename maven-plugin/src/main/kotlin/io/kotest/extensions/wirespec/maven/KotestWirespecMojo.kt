package io.kotest.extensions.wirespec.maven

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
class KotestWirespecMojo : AbstractMojo() {

    @Parameter(property = "kotestWirespec.basePackage", required = true)
    lateinit var basePackage: String

    @Parameter(property = "kotestWirespec.generatedPackage")
    var generatedPackage: String? = null

    @Parameter(property = "kotestWirespec.spring")
    var spring: Boolean? = null

    @Parameter(defaultValue = "\${project.build.directory}/wirespec")
    lateinit var extractedDir: File

    @Parameter(defaultValue = "\${project.build.directory}/generated-sources/wirespec")
    lateinit var generatedDir: File

    /**
     * Folder of `.ws` contracts to compile. When set, this is always the
     * compile input — even with spring=true, in which case the extractor
     * writes its emitted `.ws` files here (use a dedicated directory). When
     * unset, the input defaults to [extractedDir] (spring=true) or
     * `src/test/wirespec` (spring=false).
     */
    @Parameter(property = "kotestWirespec.wirespecPath")
    var wirespecPath: File? = null

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

        val springEnabled = spring ?: hasSpringBootOnClasspath()

        val extractorOutput = wirespecPath ?: extractedDir
        val inputDir = wirespecPath
            ?: if (springEnabled) extractedDir else File(project.basedir, "src/test/wirespec")

        if (springEnabled) {
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
                    element("output", extractorOutput.absolutePath),
                ),
                env,
            )
        } else {
            log.info(
                "Skipping wirespec-spring-extractor (kotestWirespec.spring = false). " +
                    "Using .ws files at ${inputDir.absolutePath}.",
            )
        }

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
                element("input", inputDir.absolutePath),
                element("output", generatedDir.absolutePath),
                element("packageName", effectivePackage),
                element("emitterClass", EMITTER_FQCN),
            ),
            env,
        )

        project.addTestCompileSourceRoot(generatedDir.absolutePath)
    }

    private fun hasSpringBootOnClasspath(): Boolean =
        project.dependencies.any { it.groupId == "org.springframework.boot" }

    private companion object {
        const val EXTRACTOR_GROUP = "community.flock.wirespec.spring"
        const val EXTRACTOR_ARTIFACT = "wirespec-spring-extractor-maven-plugin"

        const val WIRESPEC_GROUP = "community.flock.wirespec.plugin.maven"
        const val WIRESPEC_ARTIFACT = "wirespec-maven-plugin"

        const val EMITTER_GROUP = "community.flock.wirespec.kotest"
        const val EMITTER_ARTIFACT = "kotest-wirespec-emitter"
        const val EMITTER_FQCN =
            "io.kotest.extensions.wirespec.emitter.TypesafeDslEmitter"

        // Versions are injected at build time (see maven-plugin/build.gradle.kts
        // processResources → kotest-wirespec-versions.properties), so the
        // released plugin pins released coordinates and the emitter version
        // tracks this plugin's own version. WIRESPEC_VERSION must match the
        // wirespecVersion the emitter is built against (gradle.properties) —
        // mismatched versions cause Arrow 1.x vs 2.x classloader
        // incompatibility when the upstream compiler and our emitter share a
        // plugin realm.
        private val versions: java.util.Properties by lazy {
            java.util.Properties().apply {
                KotestWirespecMojo::class.java
                    .getResourceAsStream("/kotest-wirespec-versions.properties")
                    ?.use { load(it) }
                    ?: error("kotest-wirespec-versions.properties not found on the plugin classpath")
            }
        }

        val EXTRACTOR_VERSION: String get() = versions.getProperty("extractorVersion")
        val WIRESPEC_VERSION: String get() = versions.getProperty("wirespecVersion")
        val EMITTER_VERSION: String get() = versions.getProperty("emitterVersion")
    }
}
