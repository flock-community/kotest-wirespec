# Rename to `kotest-wirespec` with auto-loaded Spring context

**Date:** 2026-05-25
**Status:** Design — awaiting implementation plan.

## Summary

Rename the project from `kotest-extensions-spring-wirespec` to `kotest-wirespec`. Group, packages, plugin id, DSL extension, and Maven plugin all move to the `io.kotest.extensions.wirespec` namespace. Spring integration is extracted into a separate module (`kotest-wirespec-spring`) that auto-registers itself through a `ServiceLoader`-based SPI in core. Users get the Spring transports (`MockMvc`, `WebClient`, `EmbeddedKafka`) and the upstream `SpringExtension` lifecycle by adding `kotest-wirespec-spring` to the test classpath — no `extension(...)` call, no project-local Spring wrapper. The Gradle/Maven plugins gain a `spring` boolean that defaults to auto-detection of the Spring Boot plugin/dependency.

This is a clean break. README labels the project pre-release; no deprecated re-exports or type-aliases.

## Goals

1. The plugin and runtime should be named for what they primarily *do* (drive Wirespec contract tests from Kotest), not for the single framework they currently support.
2. Spring stays a first-class context but is no longer a hard runtime dependency. A user with no Spring on the classpath can still use the core scenario DSL with a hand-built `WirespecTestContext`.
3. The `WirespecSpec` user code should be identical whether Spring is present or not. Auto-detection is the mechanism; the user doesn't write `extension(SpringSpecExtension)`.
4. Drop the project-local `SpringSpecExtension` wrapper. The upstream `io.kotest:kotest-extensions-spring-jvm:6.1.11` is Kotest 6 native — use it directly.
5. Leave room for future non-Spring extractors (Ktor, Micronaut, …) without further surface-area renames.

## Non-goals

- No back-compat shims. Old packages and artifact ids disappear.
- No new transports beyond what the current runtime already supports (MockMvc, WebClient, in-memory channel, EmbeddedKafka). The rename does not introduce features.
- No change to the DSL semantics (`scenario { … }`, `checkAll`, `.expecting<…>()`, etc.).

## Module layout

| Directory | Kotlin package root | Published GAV / plugin id |
|---|---|---|
| `core/` (rename of `runtime/`, Spring code removed) | `io.kotest.extensions.wirespec.*` | `io.kotest.extensions.wirespec:kotest-wirespec` |
| `spring/` (NEW — extracted from `runtime/`) | `io.kotest.extensions.wirespec.spring.*` | `io.kotest.extensions.wirespec:kotest-wirespec-spring` |
| `emitter/` | `io.kotest.extensions.wirespec.emitter.*` | `io.kotest.extensions.wirespec:kotest-wirespec-emitter` |
| `gradle-plugin/` | `io.kotest.extensions.wirespec.gradle.*` | Plugin id `io.kotest.extensions.wirespec` (no `.spring`) |
| `maven-plugin/` | `io.kotest.extensions.wirespec.maven.*` | `io.kotest.extensions.wirespec:kotest-wirespec-maven-plugin` |
| `example/` | `io.kotest.extensions.wirespec.example.*` | not published |

Root `rootProject.name` → `kotest-wirespec`. `gradle.properties#group` → `io.kotest.extensions.wirespec`.

## Core module (`kotest-wirespec`)

### Package layout

- `io.kotest.extensions.wirespec` — `WirespecSpec`, `WirespecTestContext`, `WirespecChannelContext`, `Scenario`, the `wirespec(ctx) { … }` override helper.
- `io.kotest.extensions.wirespec.dsl` — `ScenarioBuilder`, `Input`, `Step`, `ResultRef`, `EndpointCallBuilder`, `ChannelCallBuilder`, `ArbReceiver`.
- `io.kotest.extensions.wirespec.runtime` — `ScenarioRunner`.
- `io.kotest.extensions.wirespec.channel` — `MessageTransport`, `InMemoryMessageTransport`.
- `io.kotest.extensions.wirespec.validation` — `ChannelValidator`.
- `io.kotest.extensions.wirespec.context` — **NEW.** The provider SPI (see below).

### Dependencies

`api`:
- `community.flock.wirespec.integration:wirespec-jvm`
- `community.flock.wirespec.integration:jackson-jvm`
- `community.flock.wirespec.integration:kotest-jvm`
- `io.kotest:kotest-runner-junit5`
- `io.kotest:kotest-property`
- `io.kotest:kotest-assertions-core`
- `org.jetbrains.kotlin:kotlin-reflect`
- `com.fasterxml.jackson.module:jackson-module-kotlin`

Deleted from current `runtime/build.gradle.kts`:
- `spring-boot-starter-test`, `spring-boot-starter-webflux` (move to spring module)
- `jakarta.servlet:jakarta.servlet-api` (move to spring module)
- `spring-kafka` / `spring-kafka-test` (move to spring module as `compileOnly`)
- `org.jetbrains.kotlinx:kotlinx-coroutines-reactor` (move to spring module — only `WebClientTransportation` needs it)
- `io.kotest.extensions:kotest-extensions-spring:1.3.0` (replaced by upstream Kotest 6 artifact in spring module)
- The `exclude(group = "io.kotest", module = "kotest-framework-api")` block (unnecessary once we're on `io.kotest:kotest-extensions-spring-jvm:6.1.11`).

### `WirespecSpec`

```kotlin
package io.kotest.extensions.wirespec

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.wirespec.context.ContextRegistry
import io.kotest.extensions.wirespec.dsl.ScenarioBuilder
import io.kotest.property.RandomSource
import io.kotest.property.checkAll

abstract class WirespecSpec(body: WirespecSpec.() -> Unit = {}) : FunSpec() {

    init {
        ContextRegistry.providers
            .mapNotNull { it.specExtension() }
            .forEach { extension(it) }
        body()
    }

    open val endpointCtx: WirespecTestContext by lazy {
        ContextRegistry.providers.firstNotNullOfOrNull { it.endpointContext(this) }
            ?: error(
                "No WirespecTestContext available. Override `endpointCtx`, " +
                    "or add `io.kotest.extensions.wirespec:kotest-wirespec-spring` " +
                    "to the test classpath for Spring auto-detection.",
            )
    }

    open val channelCtx: WirespecChannelContext? by lazy {
        ContextRegistry.providers.firstNotNullOfOrNull { it.channelContext(this) }
    }

    fun test(name: String, iterations: Int = 1, body: ScenarioBuilder.() -> Unit) {
        super.test(name) {
            if (iterations <= 1) {
                runScenarioOnce(endpointCtx, channelCtx, RandomSource.seeded(System.nanoTime()), body)
            } else {
                checkAll<Int>(iterations = iterations) {
                    runScenarioOnce(endpointCtx, channelCtx, randomSource(), body)
                }
            }
        }
    }
}
```

`SpringWirespecSpec` is **deleted**; `WirespecSpec` is the only base class. Lifecycle, context resolution, and the property-based `test(...)` overload move down into the base.

### Context provider SPI

```kotlin
package io.kotest.extensions.wirespec.context

import io.kotest.core.extensions.SpecExtension
import io.kotest.core.spec.Spec
import io.kotest.extensions.wirespec.WirespecChannelContext
import io.kotest.extensions.wirespec.WirespecTestContext
import java.util.ServiceLoader

interface ContextProvider {
    fun specExtension(): SpecExtension? = null
    fun endpointContext(spec: Spec): WirespecTestContext? = null
    fun channelContext(spec: Spec): WirespecChannelContext? = null
}

internal object ContextRegistry {
    val providers: List<ContextProvider> by lazy {
        ServiceLoader.load(ContextProvider::class.java, ContextProvider::class.java.classLoader)
            .toList()
    }
}
```

Discovery rules:
- Multiple providers are allowed; resolution uses the first non-null result, so the order is determined by classpath registration. With only one provider (`spring`) shipped today, no ordering policy is needed.
- A provider missing optional infrastructure (e.g. no `MockMvc` bean) returns `null`. The base spec's lazy initialiser then either falls back to another provider or surfaces a clear error pointing at the override.

## Spring module (`kotest-wirespec-spring`)

### Package layout

- `io.kotest.extensions.wirespec.spring` — `SpringContextProvider`, `WebClientTransportation`, `MockMvcTransportation`, `EmbeddedKafkaMessageTransport`.

That's the whole module. No `kotest/` sub-package, no wrapper extension.

### Dependencies

`api`:
- `project(":core")`
- `org.springframework.boot:spring-boot-starter-test:3.4.1` (with the `junit-vintage-engine` exclusion preserved)
- `org.springframework.boot:spring-boot-starter-webflux:3.4.1`
- `jakarta.servlet:jakarta.servlet-api:6.0.0`
- `io.kotest:kotest-extensions-spring-jvm:6.1.11` — Kotest 6 native, no exclusions required
- `org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2`

`compileOnly` (channel deps remain opt-in per the existing design):
- `org.springframework.kafka:spring-kafka:3.3.0`
- `org.springframework.kafka:spring-kafka-test:3.3.0`

### `SpringContextProvider`

```kotlin
package io.kotest.extensions.wirespec.spring

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import io.kotest.core.extensions.SpecExtension
import io.kotest.core.spec.Spec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.wirespec.WirespecChannelContext
import io.kotest.extensions.wirespec.WirespecTestContext
import io.kotest.extensions.wirespec.context.ContextProvider
import org.springframework.context.ApplicationContext
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.test.web.servlet.MockMvc
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

class SpringContextProvider : ContextProvider {

    override fun specExtension(): SpecExtension = SpringExtension

    override fun endpointContext(spec: Spec): WirespecTestContext? {
        val app = applicationContextOf(spec) ?: return null
        val mvc = app.getBeanProvider(MockMvc::class.java).getIfAvailable() ?: return null
        return WirespecTestContext(
            transportation = MockMvcTransportation(mvc),
            serialization = WirespecSerialization(jacksonObjectMapper()),
        )
    }

    override fun channelContext(spec: Spec): WirespecChannelContext? {
        val app = applicationContextOf(spec) ?: return null
        // EmbeddedKafkaBroker is published as a singleton bean by @EmbeddedKafka.
        val broker = runCatching {
            app.getBeanProvider(EmbeddedKafkaBroker::class.java).getIfAvailable()
        }.getOrNull() ?: return null
        return WirespecChannelContext(
            messaging = EmbeddedKafkaMessageTransport(app),
            serialization = WirespecSerialization(jacksonObjectMapper()),
        )
    }

    private fun applicationContextOf(spec: Spec): ApplicationContext? =
        spec::class.memberProperties
            .firstOrNull { ApplicationContext::class.java.isAssignableFrom(it.returnType.classifier.let { c -> (c as? kotlin.reflect.KClass<*>)?.java } ?: Any::class.java) }
            ?.also { it.isAccessible = true }
            ?.call(spec) as? ApplicationContext
}
```

(The reflective `applicationContextOf` is roughly sketched — the real implementation will walk the spec's properties safely.)

Registration:

```
spring/src/main/resources/META-INF/services/io.kotest.extensions.wirespec.context.ContextProvider
```
containing the single FQCN `io.kotest.extensions.wirespec.spring.SpringContextProvider`.

### User-facing implication

User code becomes:

```kotlin
import io.kotest.extensions.wirespec.WirespecSpec
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest(classes = [App::class])
@AutoConfigureMockMvc
class PetScenariosSpec : WirespecSpec({
    test("pet CRUD") {
        val petId = createPet.returning<CreatePet.Response201, String> { it.body.id }
        getPet.path(petId).expecting<GetPet.Response200>()
    }
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
```

The `@Autowired applicationContext` declaration is the one piece of Spring glue the user keeps writing — `SpringContextProvider` finds it reflectively and resolves transports from it.

## Emitter module (`kotest-wirespec-emitter`)

Pure rename — `io.kotest.extensions.wirespec.emitter.*` → `io.kotest.extensions.wirespec.emitter.*`. No code or behaviour change. Golden test fixtures and their expected outputs get the same find-and-replace. Published group flips to `io.kotest.extensions.wirespec`.

## Gradle plugin

### Identity

- Plugin id: `io.kotest.extensions.wirespec` (was `io.kotest.extensions.wirespec`)
- Plugin class: `io.kotest.extensions.wirespec.gradle.KotestWirespecPlugin`
- DSL extension: `kotestWirespec { … }` (was `kotestWirespecSpring { … }`)
- `displayName` / `description` updated; no longer says "Spring" in the title (still references Spring as one supported extractor).

### `KotestWirespecExtension`

```kotlin
abstract class KotestWirespecExtension @Inject constructor(objects: ObjectFactory) {
    /** Base package whose `@RestController`s are scanned, when Spring extraction is enabled. */
    abstract val basePackage: Property<String>

    /** Package name for generated Kotlin sources. Defaults to `<basePackage>.generated`. */
    abstract val generatedPackage: Property<String>

    /**
     * Enable the wirespec Spring extractor. Defaults to `true` when the
     * `org.springframework.boot` plugin is applied to this project, otherwise
     * `false`. Set explicitly to override the auto-detection.
     */
    abstract val spring: Property<Boolean>
}
```

In `KotestWirespecPlugin.apply`:

```kotlin
val extension = project.extensions.create("kotestWirespec", KotestWirespecExtension::class.java)

extension.spring.convention(
    project.provider { project.plugins.hasPlugin("org.springframework.boot") }
)

project.pluginManager.apply("community.flock.wirespec.plugin.gradle")

extension.spring.finalizeValueOnRead()
if (extension.spring.get()) {
    project.pluginManager.apply("community.flock.wirespec.spring.extractor")
    val extractorExt = project.extensions.getByType(WirespecExtractorExtension::class.java)
    extractorExt.outputDir.set(extractedDir)
    extractorExt.basePackage.set(extension.basePackage)
}

// wirespecKotlin task: same wiring as today, with the input dir conditional
// on whether extraction ran.
```

When `spring = false`, the plugin still wires the `wirespecKotlin` (compile + emit) task but expects user-supplied `.ws` files under a configurable input directory (default `src/test/wirespec/`). The extractor task is not applied.

## Maven plugin

### Identity

- ArtifactId: `kotest-wirespec-maven-plugin`
- Mojo class: `io.kotest.extensions.wirespec.maven.KotestWirespecMojo`
- Goal name: `generate` (unchanged)
- Parameter prefix: `kotestWirespec.*` (was `kotestWirespecSpring.*`)

### `spring` parameter

```kotlin
@Parameter(property = "kotestWirespec.spring")
var spring: Boolean? = null
```

When unset, auto-detect by scanning `project.dependencies` for any `org.springframework.boot:*` artifact. When `false`, skip the `wirespec-spring-extractor-maven-plugin` invocation; expect `.ws` files at the configured input directory.

Constants in the Mojo (`EXTRACTOR_*`, `WIRESPEC_*`, `EMITTER_*`) updated for the new GAV coordinates. The `EMITTER_FQCN` reference changes to `io.kotest.extensions.wirespec.emitter.TypesafeDslEmitter`.

### Plugin descriptor

`src/main/resources-template/META-INF/maven/plugin.xml` updated with the new groupId, artifactId, and FQCN.

### Integration test fixture

`maven-plugin/src/test/resources/fixture/pom.xml` and `fixture/src/test/kotlin/example/PetSmokeSpec.kt` updated to use the new coordinates and imports.

## Build system changes

- `gradle.properties`:
  - `group=io.kotest.extensions.wirespec`
  - `kotestSpringExtensionVersion=6.1.11` (replaces `1.3.0`; module changes from `io.kotest.extensions:kotest-extensions-spring` to `io.kotest:kotest-extensions-spring-jvm`).
- Root `settings.gradle.kts`:
  - `rootProject.name = "kotest-wirespec"`
  - `include(":core", ":spring")` (replaces `:runtime`)
  - The `includeBuild("emitter")` / `includeBuild("maven-plugin")` lines and the `pluginManagement { includeBuild("gradle-plugin") }` block remain; only the rootProject names inside those sub-builds change.
- Per-module `settings.gradle.kts` files: rootProject names updated to match the new published artifactIds (the comment in `emitter/settings.gradle.kts` explains why this matters for composite-build capability resolution).
- Per-module `build.gradle.kts`: publication `artifactId` strings updated; `group =` lines (where present) updated.

## Documentation

- `README.md` rewritten to:
  - Lead with `id("io.kotest.extensions.wirespec")` (no `.spring`).
  - Show the `spring = true|false` knob.
  - Show that `SpringExtension` from `io.kotest:kotest-extensions-spring` is what runs the lifecycle — no project-local wrapper.
  - Update the imports in the code samples (`io.kotest.extensions.wirespec.WirespecSpec`, etc.).
  - Update the Maven coordinates block.
- Existing design docs under `docs/superpowers/specs/` and plans under `docs/superpowers/plans/` are not rewritten; they're historical.

## Memory note

The existing memory file `feedback_spring_testing.md` ("don't create a custom `SpringWirespecExtension`") is reinforced by this design: the rename actually deletes the project-local wrapper. Once implementation is done, update that memory's *Why* to cite the rename as the resolution rather than a pending preference.

## Migration order (informs the implementation plan)

1. Rename packages and artifact ids across all modules in a single pass (mechanical find-and-replace inside Kotlin sources, build scripts, plugin descriptors, golden fixtures). Run all tests; everything still passes because the structure hasn't changed.
2. Bump `kotest-extensions-spring` to `io.kotest:kotest-extensions-spring-jvm:6.1.11`. Delete `SpringSpecExtension.kt` and the framework-api exclusion. Update the in-tree `SpringWirespecSpec.init` to mount `SpringExtension` directly. Run tests.
3. Introduce `core/spring/` module split. Move Spring-specific files into `spring/`. Add the SPI in core. Remove Spring deps from core. Run tests.
4. Replace `SpringWirespecSpec` with `WirespecSpec` + `SpringContextProvider`. Migrate `example/` specs. Run tests.
5. Update the Gradle plugin: rename extension, add `spring` flag with auto-detect convention. Update the Maven plugin: rename mojo, add `spring` parameter with auto-detect. Run plugin tests.
6. README + memory update.

Each step is independently verifiable. The order keeps `:example` green at every checkpoint, which is the strongest signal that the public surface still works end-to-end.

## Risks & open questions

- **Reflective `ApplicationContext` lookup.** The `SpringContextProvider` walks the spec's Kotlin properties looking for an `ApplicationContext`-typed field. Failure modes: a spec that names the field something exotic, or doesn't declare it at all. Mitigation: when the lookup fails, raise an error message that names the missing field type and suggests the standard `@Autowired protected lateinit var applicationContext: ApplicationContext` declaration.
- **`spring = false` input directory.** Not addressed in detail here — the design assumes a sensible default (`src/test/wirespec/`) but doesn't formalise the configurability. The implementation plan should decide whether `inputDir` becomes a separate `kotestWirespec { … }` property or stays implicit.
- **SPI ordering policy.** Trivial today (one provider), worth a one-line note in `ContextRegistry` for future contributors. Documented as "first non-null wins; classpath order"; if multiple providers ever ship together, we revisit.
- **`SpringExtension` vs `SpringTestExtension(Root)` from upstream.** The kotest.io docs ([Kotest Spring extension](https://kotest.io/docs/extensions/spring.html)) call out `SpringExtension` as the public entry point. Implementation should default to `SpringExtension` and only consider `SpringTestExtension(Root)` if a lifecycle issue surfaces — both ship in 6.1.11.
