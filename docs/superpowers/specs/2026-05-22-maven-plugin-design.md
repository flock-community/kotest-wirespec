# Maven plugin — design

**Status:** approved 2026-05-22
**Owner:** Willem Veelenturf
**Related:** existing Gradle plugin at `plugin/` (`io.kotest.extensions.wirespec`)

## Goal

Provide a Maven equivalent of the existing Gradle plugin: a single user-facing
declaration that extracts a Wirespec contract from Spring controllers, runs the
Wirespec compiler with the project's `TypesafeDslEmitter`, and registers the
generated Kotlin as a test source root — so a Maven-based consumer gets the
same "one block, zero ceremony" UX as the Gradle consumer.

## Non-goals (this iteration)

- Maven Central / OSSRH release configuration. Local install + integration
  testing only; releasing alongside the Gradle plugin is a follow-up.
- A separate `clean` mojo. Standard `maven-clean-plugin` already removes
  `target/`, which covers extracted `.ws` and generated `.kt` outputs.
- IDE sync hooks. IntelliJ's Maven importer picks up
  `addTestCompileSourceRoot(...)` automatically.
- Migrating `example/` from Gradle to Maven. The Maven flow is validated
  through a dedicated integration-test fixture instead.

## Constraints

- Built with Gradle to stay consistent with the rest of the repo (the existing
  Gradle plugin and emitter are Gradle builds; introducing a Maven build for a
  single new module is overkill).
- Java toolchain 21, matching `plugin/`.
- Must work with Maven 3.9+ (the upstream extractor mojo requires it).
- Must run after the user's main `compile` so the extractor can scan compiled
  controller classes. Default phase: `generate-test-sources` (runs after
  `compile` and `process-classes` in the standard lifecycle).

## User-facing contract

A consumer adds exactly one plugin block to their `pom.xml`:

```xml
<plugin>
  <groupId>io.kotest.extensions</groupId>
  <artifactId>kotest-extensions-spring-wirespec-maven-plugin</artifactId>
  <version>0.1.0</version>
  <executions>
    <execution>
      <goals><goal>generate</goal></goals>
      <configuration>
        <basePackage>com.example.api</basePackage>
      </configuration>
    </execution>
  </executions>
</plugin>
```

No additional Wirespec or extractor plugin declarations are required. The
wrapper plugin pulls them in internally.

### Configuration parameters

| Parameter         | Type   | Required | Default                                                | Notes                                                            |
| ----------------- | ------ | -------- | ------------------------------------------------------ | ---------------------------------------------------------------- |
| `basePackage`     | String | yes      | —                                                      | Package whose `@RestController`s the extractor scans.            |
| `generatedPackage`| String | no       | `<basePackage>.generated`                              | Package for the generated Kotlin sources.                        |
| `extractedDir`    | File   | no       | `${project.build.directory}/wirespec/extracted`        | Where `.ws` files land.                                          |
| `generatedDir`    | File   | no       | `${project.build.directory}/generated-sources/wirespec`| Where the typed DSL Kotlin sources land. Added as a test source. |

User-property overrides: `kotestWirespecSpring.basePackage`,
`kotestWirespecSpring.generatedPackage` (the rest are not exposed as
properties — paths inside `target/` should not vary across invocations).

## Architecture

The wrapper plugin is pure orchestration. Internally, `generate` delegates to
two upstream Maven plugins via
[`org.twdata.maven:mojo-executor`](https://github.com/mojohaus/mojo-executor),
then registers the generated source root on the `MavenProject`. This mirrors
the Gradle plugin (which is also delegation: it applies and configures
upstream plugins).

```
mvn verify
  └─ generate-test-sources
        └─ kotest-wirespec:generate
              1. mojo-executor → community.flock.wirespec.spring:
                   wirespec-spring-extractor-maven-plugin:extract
                     basePackage = <user>
                     output      = target/wirespec/extracted
              2. mojo-executor → community.flock.wirespec.plugin.maven:
                   wirespec-maven-plugin:compile
                     input        = target/wirespec/extracted
                     output       = target/generated-sources/wirespec
                     packageName  = <basePackage>.generated
                     emitterClass = io.kotest.extensions.wirespec
                                    .emitter.TypesafeDslEmitter
              3. project.addTestCompileSourceRoot(generatedDir)
```

### Pinned upstream versions (compiled into the wrapper)

- `community.flock.wirespec.spring:wirespec-spring-extractor-maven-plugin:0.0.5`
- `community.flock.wirespec.plugin.maven:wirespec-maven-plugin:0.17.20`

Versions are constants in the mojo. Bumping them is a release of this
plugin — same model as the Gradle plugin's pinned versions in
`plugin/build.gradle.kts`.

### Emitter classpath

The upstream `wirespec-maven-plugin` accepts `emitterClass` as a fully-qualified
class name. For its plugin realm to find `TypesafeDslEmitter`, we pass the
emitter artifact as a `<dependency>` on the upstream plugin via
`MojoExecutor.plugin(g, a, v, dependencies)`. This requires the emitter to be a
publishable artifact with a stable Maven coordinate:

- `io.kotest.extensions:kotest-extensions-spring-wirespec-emitter:<version>`

The `emitter/` subproject is currently composite-included only. As part of this
work it must also produce a publishable artifact resolvable from
`~/.m2/repository` (for local integration testing) so the upstream plugin's
realm can load it. This is a small build wiring change in `emitter/`, not a
code change.

## Module layout

```
kotest-spring/
├── plugin/                    (existing Gradle plugin — unchanged)
├── emitter/                   (publish to mavenLocal — small wiring change)
├── maven-plugin/              (NEW — built by Gradle)
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── kotlin/io/kotest/extensions/spring/wirespec/maven/
│       │   │   └── KotestWirespecSpringMojo.kt
│       │   └── resources/META-INF/maven/
│       │       └── plugin.xml               (hand-written template)
│       └── test/
│           ├── kotlin/.../MavenInvokerIT.kt (integration test)
│           └── resources/fixture/           (sample consumer POM + sources)
└── settings.gradle.kts        (composite-include maven-plugin)
```

## Build wiring

`maven-plugin/build.gradle.kts`:

- Kotlin JVM, toolchain 21.
- Dependencies:
  - `org.apache.maven:maven-plugin-api:3.9.6` — compile-only
  - `org.apache.maven:maven-core:3.9.6` — compile-only
  - `org.apache.maven.plugin-tools:maven-plugin-annotations:3.13.1` — compile-only
  - `org.twdata.maven:mojo-executor:2.4.0` — runtime/implementation
  - `org.apache.maven:maven-invoker:3.3.0` — test only
  - `org.junit.jupiter:junit-jupiter:5.10.2` — test only
- `processResources` is configured to expand a hand-written
  `META-INF/maven/plugin.xml` template, substituting `${project.version}`.
  This avoids pulling `maven-plugin-plugin` into a Gradle build for a single
  mojo. The descriptor lists one mojo (`generate`), its parameters, default
  phase (`generate-test-sources`), and dependency-resolution scope (`test`).
- `publishing` block configured for `publishToMavenLocal` with packaging
  `maven-plugin` so the artifact installs into `~/.m2/repository` for the
  integration test to consume.

`emitter/build.gradle.kts` gets the minimal additions needed to also publish
to `mavenLocal` (coordinates `io.kotest.extensions:kotest-extensions-spring-wirespec-emitter:<version>`).
Existing composite-include usage is preserved for the Gradle plugin.

`settings.gradle.kts` gets `includeBuild("maven-plugin")` (matching the
pattern used for `plugin/` and `emitter/`).

## Mojo skeleton

```kotlin
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

    @Parameter(defaultValue = "\${project}", readonly = true)
    lateinit var project: MavenProject

    @Parameter(defaultValue = "\${session}", readonly = true)
    lateinit var session: MavenSession

    @Component
    lateinit var pluginManager: BuildPluginManager

    override fun execute() {
        val env = executionEnvironment(project, session, pluginManager)

        executeMojo(
            plugin(
                groupId("community.flock.wirespec.spring"),
                artifactId("wirespec-spring-extractor-maven-plugin"),
                version(EXTRACTOR_VERSION),
            ),
            goal("extract"),
            configuration(
                element("basePackage", basePackage),
                element("output", extractedDir.absolutePath),
            ),
            env,
        )

        executeMojo(
            plugin(
                groupId("community.flock.wirespec.plugin.maven"),
                artifactId("wirespec-maven-plugin"),
                version(WIRESPEC_VERSION),
                dependencies(
                    listOf(
                        dependency(
                            "io.kotest.extensions",
                            "kotest-extensions-spring-wirespec-emitter",
                            EMITTER_VERSION,
                        ),
                    ),
                ),
            ),
            goal("compile"),
            configuration(
                element("input", extractedDir.absolutePath),
                element("output", generatedDir.absolutePath),
                element("packageName", generatedPackage ?: "$basePackage.generated"),
                element(
                    "emitterClass",
                    "io.kotest.extensions.wirespec.emitter.TypesafeDslEmitter",
                ),
            ),
            env,
        )

        project.addTestCompileSourceRoot(generatedDir.absolutePath)
    }
}
```

`EXTRACTOR_VERSION`, `WIRESPEC_VERSION`, `EMITTER_VERSION` are `const val`s in
the same file (or a sibling `Versions.kt`).

## Verification

Integration test in `maven-plugin/src/test/`:

1. `MavenInvokerIT` (JUnit 5) runs once `publishToMavenLocal` has been invoked
   for `emitter` and `maven-plugin`. The Gradle `test` task depends on both
   `publish` tasks.
2. The test copies `src/test/resources/fixture/` to a temp directory. The
   fixture is a minimal Spring Boot Maven project: one `@RestController` with
   a single `GET` endpoint and a Kotest spec under `src/test/kotlin` that
   exercises the generated DSL.
3. The test invokes `mvn -B verify` via `maven-invoker`, with the local repo
   pointed at `~/.m2/repository` and `kotestWirespecSpring.basePackage` set
   via `<properties>` in the fixture POM.
4. Assertions:
   - Build exit code is 0.
   - `target/wirespec/extracted/*.ws` exists.
   - `target/generated-sources/wirespec/.../endpoint/*.kt` and
     `target/generated-sources/wirespec/.../kotest/*Dsl.kt` exist.
   - Surefire reports show the Kotest spec ran and passed.

This mirrors how `example/` validates the Gradle plugin end-to-end today, and
catches the same class of regressions (extractor → emitter → DSL pipeline)
through the Maven entry point.

## Open risks

- **mojo-executor + Maven 3.9 compatibility.** mojo-executor 2.4.0 supports
  Maven 3.9. Verified against the upstream README; if a 3.x breakage surfaces
  during implementation, fall back to direct library calls (approach C from
  brainstorming) for that step.
- **Hand-written `plugin.xml` drifting from `@Mojo` annotations.** Mitigated
  by keeping the mojo to a single goal with stable parameters; a follow-up
  could swap to descriptor generation if the surface grows.
- **Emitter artifact resolution at runtime.** The upstream `wirespec-maven-plugin`
  must be able to load `TypesafeDslEmitter` from its plugin realm. If
  mojo-executor's `dependencies(...)` doesn't propagate the dep into the
  upstream plugin's classloader as expected, fallback is to require users to
  declare the emitter as a `<dependency>` on the wrapper plugin block — still
  one declaration, just with one extra child element.
