# CI + Maven Central Publishing — Design

**Date:** 2026-06-11
**Status:** Draft, pending review
**Reference:** Modeled on [flock-community/wirespec-spring-extractor](https://github.com/flock-community/wirespec-spring-extractor)

## Goal

Add a GitHub Actions CI pipeline and Maven Central publishing to the
`kotest-wirespec` repository, mirroring the setup in the
`wirespec-spring-extractor` reference repo, adapted to this repo's
composite-build structure.

## Decisions (locked)

| Topic | Decision |
| --- | --- |
| Modules published to Central | `core`, `spring`, `emitter`, `maven-plugin`, `gradle-plugin` (all five) |
| Group / namespace | `io.kotest.extensions.wirespec` → **`community.flock.wirespec.kotest`** |
| Gradle plugin id | `io.kotest.extensions.wirespec` → **`community.flock.wirespec.kotest`** |
| `wirespec` dependency version | `0.19.3-RC.1` |
| `wirespec-spring-extractor` version | `0.0.13` |
| Publishing tool | vanniktech `com.vanniktech.maven.publish.base` `0.30.0`, Central Portal, `automaticRelease = true` |
| Wiring approach | Inline per build (Approach A) |
| Branch / sequencing | Layer onto `feature/scenario-dsl-ergonomics`, flipping SNAPSHOT → released as part of this change |

Artifact IDs are **unchanged**: `kotest-wirespec`, `kotest-wirespec-spring`,
`kotest-wirespec-emitter`, `kotest-wirespec-maven-plugin`, and the gradle
plugin.

## The structural challenge

The reference repo is a **single** Gradle build whose root `build.gradle.kts`
configures vanniktech for every subproject in one `subprojects {}` block. This
repo is **four separate Gradle builds** joined with `includeBuild`:

- **Root build** → `core`, `spring`, `example` (real subprojects)
- **`emitter`** → own build (included so `gradle-plugin` depends on it from source)
- **`maven-plugin`** → own build
- **`gradle-plugin`** → own build (included so `:example` applies it from
  source; it in turn includes `emitter`)

A single root `subprojects {}` block cannot reach the three included builds, so
the publishing config must be applied per build.

## Approaches considered (vanniktech wiring)

- **A — Inline config per build (chosen).** Root uses the reference's
  `subprojects { plugins.withId("…") { … } }` pattern for `core` + `spring`;
  each included build (`emitter`, `maven-plugin`, `gradle-plugin`) configures
  vanniktech inline in its own `build.gradle.kts`. Self-contained and robust;
  cost is POM metadata duplicated in ~4 spots.
- **B — Shared `apply(from = …)` script.** DRY, but `apply-from` across
  composite builds with vanniktech's extension types and differing `rootDir`s
  is fragile.
- **C — buildSrc convention plugin.** Each composite build would need its own
  `buildSrc`. Overkill.

## Implementation

### 1. Version pinning (Central rejects SNAPSHOT deps in published POMs)

Set the flock dependency versions to released coordinates:

- `gradle.properties`: `wirespecVersion=0.19.3-RC.1`,
  `wirespecExtractorVersion=0.0.13`
- `core/build.gradle.kts`: `val wirespecVersion = "0.19.3-RC.1"`
- `emitter/build.gradle.kts`: `val wirespecVersion = "0.19.3-RC.1"`
- `gradle-plugin/build.gradle.kts`:
  - `community.flock.wirespec.plugin.gradle:…:0.19.3-RC.1`
  - `community.flock.wirespec.spring:wirespec-spring-extractor-gradle-plugin:0.0.13`
  - emitter dep coordinate group updated (see §2)
- `maven-plugin/build.gradle.kts`: resource-template fallbacks →
  `0.19.3-RC.1` / `0.0.13`

The **project's own** version stays `0.0.0-SNAPSHOT` as the dev default; the
release workflow overrides it (see §6). Test-fixture `…-SNAPSHOT` versions
(local-only) are left alone.

> **Reconciliation note:** commit `b2b109c` on `feature/scenario-dsl-ergonomics`
> intentionally points wirespec/extractor at `0.0.0-SNAPSHOT` + `mavenLocal`
> for local snapshot development. This publishing work **layers onto the same
> branch** and flips those back to released versions. The feature code depends
> on newer Wirespec APIs (`jackson.v2.kotlin`, `Gen`-accepting slot setters);
> `0.19.3-RC.1` is newer than the `0.19.0-RC.4` the branch previously built
> against, so it is expected to contain them — **must be verified by a green
> build**. If the feature code does not compile against `0.19.3-RC.1`, stop and
> raise it: the SNAPSHOT may contain unreleased APIs, which would block the
> release regardless.

### 2. Group change → `community.flock.wirespec.kotest`

Update `group` and the internal coordinate references that must keep matching:

- `gradle.properties` `group=`
- root `build.gradle.kts` `allprojects` group fallback
- `emitter/build.gradle.kts` `group =`
- `maven-plugin/build.gradle.kts` `group =`
- `gradle-plugin/build.gradle.kts` `group =` **and** the
  `kotest-wirespec-emitter` dependency coordinate (so the `includeBuild`
  substitution still matches → POM records the emitter at the release version)
- `maven-plugin/src/main/resources-template/.../plugin.xml` `<groupId>`
- `maven-plugin` test fixtures (`fixture/pom.xml`, `fixture-direct/pom.xml`)
  `<groupId>` references (6 occurrences) — so the integration tests resolve the
  project's own artifacts from `mavenLocal`

`example`'s own group is left unchanged (not published).

### 3. Gradle plugin id rename → `community.flock.wirespec.kotest`

The plugin's Central marker artifact publishes under a groupId equal to the
plugin-id prefix; to publish to Central under a verified namespace, the id must
live there. Update:

- `gradle-plugin/build.gradle.kts` `gradlePlugin { plugins { … id = … } }`
- `example/build.gradle.kts` `plugins { id("…") }` and the `kotestWirespec {}`
  extension still resolves (extension name is derived from the plugin
  registration name, which is unchanged)
- `settings.gradle.kts` substitution comment

The plugin's `implementationClass` and Kotlin package stay
`io.kotest.extensions.wirespec.gradle.*` (internal, not consumer-facing).

### 4. vanniktech publishing config (Approach A)

For each published module, replace the hand-rolled
`publishing { publications { create<MavenPublication>("maven") { … } } }` with
vanniktech:

```kotlin
// in plugins {}
id("com.vanniktech.maven.publish.base") version "0.30.0"   // included builds
// root build declares it `apply false`; core/spring apply it themselves

extensions.configure<MavenPublishBaseExtension> {
    configureBasedOnAppliedPlugins()
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    coordinates(group.toString(), "<artifactId>", version.toString())
    pom {
        name.set("<artifactId>")
        description.set(<existing description>)
        url.set("https://github.com/flock-community/kotest-wirespec")
        // maven-plugin only: packaging = "maven-plugin"
        licenses { license { name = "The Apache License, Version 2.0"; url = "https://www.apache.org/licenses/LICENSE-2.0.txt" } }
        developers { developer { id = "wilmveel"; name = "Willem Veelenturf"; email = "willem.veelenturf@flock.community"; organization = "Flock. Community"; organizationUrl = "https://flock.community" } }
        scm {
            connection = "scm:git:git://github.com/flock-community/kotest-wirespec.git"
            developerConnection = "scm:git:ssh://github.com:flock-community/kotest-wirespec.git"
            url = "https://github.com/flock-community/kotest-wirespec"
        }
    }
}
```

Per-module specifics:

- **Root build (`core`, `spring`):** declare
  `id("com.vanniktech.maven.publish.base") version "0.30.0" apply false` in the
  root `plugins {}`; add the plugin to `core` and `spring` `plugins {}`; the
  root `subprojects { plugins.withId("com.vanniktech.maven.publish.base") { … } }`
  block holds the shared config and maps `name` → artifactId. `example` is
  excluded (never applies the plugin).
- **`emitter`:** inline config. Remove `withSourcesJar()` (vanniktech provides
  sources + javadoc jars).
- **`maven-plugin`:** inline config with `packaging = "maven-plugin"`. Remove
  `withSourcesJar()`.
- **`gradle-plugin`:** inline config; **keep** `com.gradle.plugin-publish` for
  the Gradle Plugin Portal. vanniktech auto-configures the plugin-marker + main
  publication for `java-gradle-plugin`.

The existing build-local `publishing { repositories { … } }` (mavenLocal /
`it-repo`) blocks and the root `publishToMavenLocalAll` task are **retained** —
the maven-plugin integration test depends on them. vanniktech skips signing for
`publishToMavenLocal`, so unsigned local publishing in CI keeps working (**must
be verified**).

### 5. CI workflow — `.github/workflows/ci.yml`

On `pull_request` and `push` to `main`:

- `actions/checkout@v4`, `actions/setup-java@v4` (Temurin 21),
  `gradle/actions/setup-gradle@v4`
- Build/test all four builds. Either one root aggregator task `checkAll`
  (mirroring `publishToMavenLocalAll`, depending on each included build's
  `:check`) run as `./gradlew checkAll --stacktrace`, **or** explicit steps:
  `./gradlew build`, then `-p emitter build`, `-p maven-plugin build`,
  `-p gradle-plugin build`. The aggregator task is preferred (single command,
  one daemon).

### 6. Release workflow — `.github/workflows/release.yml`

On `release: [published]`:

- Checkout the release tag; Temurin 21; `gradle/actions/setup-gradle@v4`
- Derive `VERSION="${GITHUB_REF_NAME#v}"`
- **Propagate version via `ORG_GRADLE_PROJECT_version`** (an env var), **not**
  `-Pversion` — project properties do not propagate into `includeBuild`
  children, but this env var is read by every build in the tree. This is the
  key adaptation over the reference's single-build `-Pversion`.
- Run a root aggregator `publishToMavenCentralAll` that depends on
  `:core:publishAndReleaseToMavenCentral`,
  `:spring:publishAndReleaseToMavenCentral`, and
  `gradle.includedBuild("<name>").task(":publishAndReleaseToMavenCentral")` for
  `emitter`, `maven-plugin`, `gradle-plugin`. Invoke with
  `--no-configuration-cache`.
- Secrets (same names as the reference):
  `ORG_GRADLE_PROJECT_mavenCentralUsername` ← `SONATYPE_USERNAME`,
  `…Password` ← `SONATYPE_PASSWORD`,
  `ORG_GRADLE_PROJECT_signingInMemoryKey` ← `GPG_PRIVATE_KEY`,
  `…KeyPassword` ← `GPG_PASSPHRASE`.

The Gradle Plugin Portal release (`./gradlew -p gradle-plugin publishPlugins`)
stays manual, matching the reference (its release workflow does Central only).

## Risks to verify during implementation

1. `publishToMavenLocal` must succeed **without** signing keys (maven-plugin
   integration test relies on it in CI). vanniktech skips local signing —
   confirm the build stays green.
2. `0.19.3-RC.1` (all flock wirespec artifacts) and `0.0.13`
   (`wirespec-spring-extractor-gradle-plugin`) must exist on Central; the build
   fails fast otherwise.
3. The scenario-DSL feature code must compile against `0.19.3-RC.1` (it was
   written against a newer SNAPSHOT).
4. Five separate builds → five separate Central deployments (no single
   coordinated deployment across composite builds); each auto-releases.
5. `ORG_GRADLE_PROJECT_version` must reach the included builds' POMs **and** the
   `gradle-plugin → emitter` substituted dependency version.

## Out of scope

- Flattening the composite builds into one project (load-bearing structure).
- Introducing a Gradle version catalog (`libs.versions.toml`).
- Changing `example`'s own group / coordinates.
- Automating the Gradle Plugin Portal release.

## Resolved

1. **Branch/sequencing:** layer onto `feature/scenario-dsl-ergonomics`,
   flipping SNAPSHOT → released (`0.19.3-RC.1` / `0.0.13`) as part of this
   change. The feature code must compile against `0.19.3-RC.1` (risk #3).
2. **Release behavior:** `automaticRelease = true` — each deployment
   auto-publishes to Central, matching the reference repo.
