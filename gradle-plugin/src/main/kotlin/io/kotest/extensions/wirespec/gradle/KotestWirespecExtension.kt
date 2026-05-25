package io.kotest.extensions.wirespec.gradle

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
     * `false`. When `false`, the plugin still wires the `wirespecKotlin`
     * compile/emit task — supply `.ws` files at `src/test/wirespec/` (or
     * configure the upstream `community.flock.wirespec.plugin.gradle` plugin
     * for a different input location).
     */
    abstract val spring: Property<Boolean>
}
