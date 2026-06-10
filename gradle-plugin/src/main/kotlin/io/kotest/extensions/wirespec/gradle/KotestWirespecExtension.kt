package io.kotest.extensions.wirespec.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import javax.inject.Inject
import org.gradle.api.model.ObjectFactory

abstract class KotestWirespecExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Base package whose `@RestController`-annotated classes the extractor scans.
     * Required when [spring] is `true`.
     */
    abstract val basePackage: Property<String>

    /**
     * Package name for the generated Kotlin sources. Defaults to `<basePackage>.generated`.
     */
    abstract val generatedPackage: Property<String>

    /**
     * Enable the Wirespec Spring extractor (scan `@RestController`s in
     * [basePackage], emit `.ws` files, then compile them). Defaults to `true`
     * when `org.springframework.boot` is applied to this project, otherwise
     * `false`. When `false`, the plugin compiles `.ws` files from
     * [wirespecPath] (or `src/test/wirespec` if unset).
     */
    abstract val spring: Property<Boolean>

    /**
     * Folder of `.ws` contracts to compile. When set, this is always the
     * compile input — even with [spring] `true`, in which case the extractor
     * writes its emitted `.ws` files here before compilation (use a dedicated
     * directory; the extractor overwrites it on each run). When unset, the
     * input defaults to the extractor output dir (`spring = true`) or
     * `src/test/wirespec` (`spring = false`).
     */
    abstract val wirespecPath: DirectoryProperty
}
