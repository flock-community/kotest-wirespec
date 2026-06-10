package io.kotest.extensions.wirespec.maven

import org.apache.maven.shared.invoker.DefaultInvocationRequest
import org.apache.maven.shared.invoker.DefaultInvoker
import org.apache.maven.shared.invoker.InvocationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class MavenInvokerIT {

    @Test
    fun `fixture builds end-to-end with mvn verify`() {
        val fixtureSrc = locateFixture()
        val workDir = Files.createTempDirectory("kotest-wirespec-fixture")
        copyDir(fixtureSrc.toPath(), workDir)

        val request = DefaultInvocationRequest().apply {
            baseDirectory = workDir.toFile()
            goals = listOf("verify")
            isBatchMode = true
            javaHome = File(System.getProperty("java.home"))
        }

        val invoker = DefaultInvoker().apply {
            mavenHome = resolveMavenHome()
                ?: error("Could not locate Maven. Set MAVEN_HOME or ensure mvn is on PATH.")
        }

        val result: InvocationResult = invoker.execute(request)
        assertEquals(0, result.exitCode, "mvn verify failed in fixture (see logs above)")

        val target = workDir.resolve("target")
        val extracted = target.resolve("wirespec").toFile()
        assertTrue(
            extracted.exists() && (extracted.listFiles()?.any { it.extension == "ws" } == true),
            "Expected at least one .ws file under $extracted",
        )

        val generated = target.resolve("generated-sources/wirespec").toFile()
        val generatedFiles = generated.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            generatedFiles.any { it.path.contains("/endpoint/") },
            "Expected at least one generated endpoint .kt file; saw ${generatedFiles.map { it.path }}",
        )
        assertTrue(
            generatedFiles.any { it.path.contains("/kotest/") },
            "Expected at least one generated DSL .kt file; saw ${generatedFiles.map { it.path }}",
        )
    }

    @Test
    fun `direct mode generates from wirespecPath without running the extractor`() {
        val fixtureSrc = locateFixture("fixture-direct")
        val workDir = Files.createTempDirectory("kotest-wirespec-direct")
        copyDir(fixtureSrc.toPath(), workDir)

        val request = DefaultInvocationRequest().apply {
            baseDirectory = workDir.toFile()
            goals = listOf("test-compile")
            isBatchMode = true
            javaHome = File(System.getProperty("java.home"))
        }

        val invoker = DefaultInvoker().apply {
            mavenHome = resolveMavenHome()
                ?: error("Could not locate Maven. Set MAVEN_HOME or ensure mvn is on PATH.")
        }

        val result: InvocationResult = invoker.execute(request)
        assertEquals(0, result.exitCode, "mvn test-compile failed in direct-mode fixture (see logs above)")

        val target = workDir.resolve("target")

        // Extractor must NOT have run: no build/wirespec output dir.
        val extracted = target.resolve("wirespec").toFile()
        assertTrue(
            !extracted.exists(),
            "Expected no extractor output at $extracted in direct mode, but it exists",
        )

        // DSL generated from the hand-authored pet.ws.
        val generated = target.resolve("generated-sources/wirespec").toFile()
        val generatedFiles = generated.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            generatedFiles.any { it.path.contains("/endpoint/") },
            "Expected a generated endpoint .kt file; saw ${generatedFiles.map { it.path }}",
        )
        assertTrue(
            generatedFiles.any { it.path.contains("/kotest/") },
            "Expected a generated DSL .kt file; saw ${generatedFiles.map { it.path }}",
        )
    }

    private fun resolveMavenHome(): File? {
        // 1. Explicit env wins.
        val envHome = System.getenv("MAVEN_HOME") ?: System.getenv("M2_HOME")
        if (envHome != null) return File(envHome)

        // 2. Walk PATH for `mvn`, then resolve sibling/parent layout
        // (homebrew lays out the launcher as a symlink to the libexec dir).
        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        for (dir in pathDirs) {
            val candidate = File(dir, "mvn")
            if (candidate.isFile && candidate.canExecute()) {
                val real = candidate.toPath().toRealPath().toFile()
                // real is usually <home>/bin/mvn, so home is its grandparent.
                val home = real.parentFile?.parentFile
                if (home != null && File(home, "bin/mvn").exists()) return home
            }
        }
        return null
    }

    private fun locateFixture(name: String = "fixture"): File {
        val onClasspath = javaClass.getResource("/$name/pom.xml")
        if (onClasspath != null) {
            return File(onClasspath.toURI()).parentFile
        }
        val module = File(System.getProperty("user.dir"))
        return module.resolve("src/test/resources/$name")
    }

    private fun copyDir(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val dest = target.resolve(source.relativize(src))
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest)
                } else {
                    Files.createDirectories(dest.parent)
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
