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
        // RC limitation: wirespec 0.20.0-RC.2's `KotlinIrEmitter` hardcodes generated
        // models to `community.flock.wirespec.generated`, ignoring `packageName` (only the
        // Kotest DSL honors it). Pin packageName to that value so the generated DSL and
        // models share one package and compile together. `basePackage`/`generatedPackage`
        // therefore no longer control the generated code's package (until a wirespec release
        // restores IR-emitter packageName handling); `basePackage` still drives the Spring
        // extractor below.
        val effectivePackage = WIRESPEC_IR_GENERATED_PACKAGE

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

        log.info("Generating Kotest scenario DSL into $generatedDir (package $effectivePackage)")
        executeMojo(
            plugin(
                groupId(WIRESPEC_GROUP),
                artifactId(WIRESPEC_ARTIFACT),
                version(WIRESPEC_VERSION),
                // The wirespec compile goal reflectively loads the emitter + extension from
                // its own plugin realm, so both must be on its dependency classpath. Both
                // resolve at the same wirespec version as the compile plugin (no Arrow realm
                // mismatch).
                listOf(
                    dependency(KOTLIN_EMITTER_GROUP, KOTLIN_EMITTER_ARTIFACT, WIRESPEC_VERSION),
                    dependency(KOTEST_EXTENSION_GROUP, KOTEST_EXTENSION_ARTIFACT, WIRESPEC_VERSION),
                ),
            ),
            goal("compile"),
            configuration(
                element("input", inputDir.absolutePath),
                element("output", generatedDir.absolutePath),
                element("packageName", effectivePackage),
                // IR emitter for the Kotlin models, with wirespec's Kotest DSL layered on top.
                element("emitterClass", KOTLIN_IR_EMITTER_FQCN),
                element("extensionClasses", element("extensionClass", KOTEST_DSL_EXTENSION_FQCN)),
                // The shared `Wirespec` runtime comes from `wirespec-jvm`, so don't emit a copy.
                element("shared", "false"),
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

        // The IR Kotlin emitter (models) and the Kotest DSL extension (`<Endpoint>.call { }`)
        // both ship as wirespec artifacts at WIRESPEC_VERSION — they replace this repo's
        // former `kotest-wirespec-emitter`.
        const val KOTLIN_EMITTER_GROUP = "community.flock.wirespec.compiler.emitters"
        const val KOTLIN_EMITTER_ARTIFACT = "kotlin-jvm"
        const val KOTLIN_IR_EMITTER_FQCN = "community.flock.wirespec.emitters.kotlin.KotlinIrEmitter"

        const val KOTEST_EXTENSION_GROUP = "community.flock.wirespec.integration"
        const val KOTEST_EXTENSION_ARTIFACT = "kotest-jvm"
        const val KOTEST_DSL_EXTENSION_FQCN =
            "community.flock.wirespec.integration.kotest.extension.KotestDslExtension"

        // See the usage site: the RC IR emitter ignores `packageName` for models and always
        // writes them here, so the generated DSL is pinned to the same package.
        const val WIRESPEC_IR_GENERATED_PACKAGE = "community.flock.wirespec.generated"

        // Versions are injected at build time (see maven-plugin/build.gradle.kts
        // processResources → kotest-wirespec-versions.properties), so the released plugin
        // pins released coordinates. The emitter + Kotest extension resolve at the same
        // WIRESPEC_VERSION as the compile plugin, so they share one plugin realm without an
        // Arrow 1.x vs 2.x classloader mismatch.
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
    }
}
