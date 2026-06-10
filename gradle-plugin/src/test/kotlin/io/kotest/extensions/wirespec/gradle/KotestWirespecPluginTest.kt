package io.kotest.extensions.wirespec.gradle

import community.flock.wirespec.plugin.gradle.CompileWirespecTask
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class KotestWirespecPluginTest {

    private fun evaluatedProject(configure: (Project, KotestWirespecExtension) -> Unit): Project {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply(KotestWirespecPlugin::class.java)
        val ext = project.extensions.getByType(KotestWirespecExtension::class.java)
        ext.basePackage.set("com.example")
        configure(project, ext)
        (project as ProjectInternal).evaluate()
        return project
    }

    private fun compileInput(project: Project): File =
        project.tasks.named("wirespecKotlin", CompileWirespecTask::class.java)
            .get().input.get().asFile

    @Test
    fun `spring false without path reads src test wirespec`() {
        val project = evaluatedProject { _, ext -> ext.spring.set(false) }
        assertEquals(
            project.layout.projectDirectory.dir("src/test/wirespec").asFile,
            compileInput(project),
        )
    }

    @Test
    fun `wirespecPath overrides the compile input`() {
        val project = evaluatedProject { p, ext ->
            ext.spring.set(false)
            ext.wirespecPath.set(p.layout.projectDirectory.dir("contracts"))
        }
        assertEquals(
            project.layout.projectDirectory.dir("contracts").asFile,
            compileInput(project),
        )
    }
}
