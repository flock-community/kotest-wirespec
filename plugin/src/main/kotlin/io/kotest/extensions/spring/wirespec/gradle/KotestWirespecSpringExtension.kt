package io.kotest.extensions.spring.wirespec.gradle

import org.gradle.api.provider.Property
import javax.inject.Inject
import org.gradle.api.model.ObjectFactory

abstract class KotestWirespecSpringExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Base package whose `@RestController`-annotated classes the extractor scans.
     * Required.
     */
    abstract val basePackage: Property<String>

    /**
     * Package name for the generated Kotlin sources. Defaults to `<basePackage>.generated`.
     */
    abstract val generatedPackage: Property<String>
}
