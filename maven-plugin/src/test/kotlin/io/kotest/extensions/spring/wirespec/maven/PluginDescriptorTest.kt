package io.kotest.extensions.spring.wirespec.maven

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginDescriptorTest {

    @Test
    fun `descriptor is on the classpath`() {
        val xml = readDescriptor()
        assertNotNull(xml, "META-INF/maven/plugin.xml not found on test classpath")
    }

    @Test
    fun `projectVersion token has been substituted`() {
        val xml = readDescriptor()!!
        assertFalse(
            xml.contains("\${projectVersion}"),
            "Expected \${projectVersion} to be substituted by Gradle expand",
        )
        assertTrue(
            xml.contains("<version>"),
            "Descriptor should declare a <version>",
        )
    }

    @Test
    fun `mojo implementation FQCN matches the Kotlin class`() {
        val xml = readDescriptor()!!
        assertTrue(
            xml.contains("<implementation>io.kotest.extensions.spring.wirespec.maven.KotestWirespecSpringMojo</implementation>"),
            "Mojo implementation FQCN drifted from descriptor",
        )
    }

    @Test
    fun `goal name and phase are wired correctly`() {
        val xml = readDescriptor()!!
        assertTrue(xml.contains("<goal>generate</goal>"))
        assertTrue(xml.contains("<phase>generate-test-sources</phase>"))
        assertTrue(xml.contains("<goalPrefix>kotest-wirespec</goalPrefix>"))
    }

    private fun readDescriptor(): String? =
        javaClass.getResource("/META-INF/maven/plugin.xml")?.readText()
}
