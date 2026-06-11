# Configurable `wirespecPath` for the Gradle and Maven plugins

**Date:** 2026-06-10
**Status:** Approved (design)

## Goal

Make "point at a `.ws` source folder directly" a first-class, explicitly
configured option in both the Gradle and Maven plugins, alongside the existing
Spring extractor. Today the direct-folder path exists only implicitly: setting
`spring = false` falls back to a fixed location (`src/test/wirespec` in Gradle,
the build `extractedDir` in Maven) with no way to configure that location
through the plugin's own API.

Keep the `spring` boolean as the extractor on/off toggle. Add a single new
`wirespecPath` setting that, when set, becomes the Wirespec compile input.

## Input-resolution rule (identical for both plugins)

| `spring` | `wirespecPath` | Extractor runs? | Compile reads from        | Extractor writes to |
|----------|----------------|-----------------|---------------------------|---------------------|
| true     | unset          | yes             | `build/wirespec` (output) | `build/wirespec`    |
| true     | set            | yes             | `wirespecPath`            | `wirespecPath`      |
| false    | unset          | no              | `src/test/wirespec`       | —                   |
| false    | set            | no              | `wirespecPath`            | —                   |

Rules in prose:

- **Compile input** = `wirespecPath` if set; otherwise the extractor output dir
  when `spring = true`, or `src/test/wirespec` when `spring = false`.
- **Extractor output** (only when `spring = true`) = `wirespecPath` if set,
  otherwise the default extractor output dir.

`wirespecPath`, when set, *always* wins as the compile input — including with
`spring = true`, where the extractor writes its emitted `.ws` files into that
same folder before compilation.

## Gradle plugin

**`KotestWirespecExtension`**
- Add `abstract val wirespecPath: DirectoryProperty`.
- KDoc: explains it overrides the compile input; when `spring = true` the
  extractor writes there (caveat: a dedicated dir is recommended, since the
  extractor overwrites it each run).

**`KotestWirespecPlugin`**
- `compileTask.input` resolves to `extension.wirespecPath.orElse(defaultInputDir)`
  for the `spring = false` path (default `src/test/wirespec`, unchanged).
- In the `afterEvaluate` Spring branch:
  - `extractorExt.outputDir.set(extension.wirespecPath.orElse(extractedDir))`.
  - `compileTask.input.set(extension.wirespecPath.orElse(extractedDir))`.
  - Keep `task.dependsOn(extractWirespec)`.

The existing `build/wirespec` (`extractedDir`) and `src/test/wirespec`
(`defaultInputDir`) constants remain as the fallback defaults.

## Maven plugin

**`KotestWirespecMojo`**
- Add `@Parameter(property = "kotestWirespec.wirespecPath") var wirespecPath: File? = null`.
- Resolve:
  - `extractorOutput = wirespecPath ?: extractedDir`
  - `inputDir = wirespecPath ?: (if (springEnabled) extractedDir else File(project.basedir, "src/test/wirespec"))`
- Extractor goal `output` element → `extractorOutput.absolutePath`.
- Compile goal `input` element → `inputDir.absolutePath`.

**Behavior change:** the Maven `spring = false` default input moves from
`build/wirespec` (today's `extractedDir`) to `src/test/wirespec`, matching
Gradle. This is intentional — hand-authored `.ws` files belong under source
control, not the build dir. `extractedDir` remains the extractor's default
output and the `spring = true` default input.

## Edge cases & notes

- `spring = true` + `wirespecPath` set: the extractor overwrites the folder on
  each run. Safe for a dedicated directory; risky if mixed with hand-authored
  files. Documented in KDoc/README, not blocked.
- No new validation: `basePackage` stays required (Maven) / used for the
  generated-package default (both). Direct-folder users still set `basePackage`
  for `generatedPackage` derivation, or set `generatedPackage` explicitly.

## Testing

- **Gradle:** functional/TestKit (or task-input unit) coverage of the three
  resolution branches: `spring=true` unset, `spring=false` unset (default
  `src/test/wirespec`), and `wirespecPath` set.
- **Maven:** extend `MavenInvokerIT` with a `spring=false` + `wirespecPath`
  scenario pointing at a checked-in `.ws` folder in a fixture, asserting the DSL
  is generated from those files without invoking the extractor.
- **Docs:** README (Gradle + Maven sections) documents `wirespecPath` and the
  two modes; the maven smoke-test fixture mentions/uses it where relevant.

## Out of scope

- No `source`/`mode` enum (rejected in favor of the smaller `spring` + path
  change).
- No change to the emitter, runtime, or generated DSL shape.
