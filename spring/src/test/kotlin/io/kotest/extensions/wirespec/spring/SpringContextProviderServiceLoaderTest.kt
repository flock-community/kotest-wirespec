package io.kotest.extensions.wirespec.spring

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.wirespec.context.ContextProvider
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.util.ServiceLoader

class SpringContextProviderServiceLoaderTest : FunSpec({

    test("META-INF/services registers SpringContextProvider exactly once") {
        val classNames = ServiceLoader.load(ContextProvider::class.java)
            .map { it::class.java.name }
        classNames shouldContainExactlyInAnyOrder listOf(
            "io.kotest.extensions.wirespec.spring.SpringContextProvider",
        )
    }
})
