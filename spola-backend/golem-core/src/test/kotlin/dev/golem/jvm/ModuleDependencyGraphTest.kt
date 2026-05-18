package dev.spola.jvm

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleDependencyGraphTest {
    @Test
    fun `dependency graph resolves direct and transitive module dependencies`() {
        val modules = GradleProjectScanner().scan(fixture("multi-module-deps").toString())
        val graph = ModuleDependencyGraph()

        val direct = graph.directDependencies(modules)
        val transitive = graph.resolveTransitiveDependencies(modules)

        assertTrue(direct.any { it.moduleName == ":service" && it.dependency == ":api" && it.type == "api" })
        assertTrue(direct.any { it.moduleName == ":app" && it.dependency == ":service" && it.type == "implementation" })
        assertEquals(listOf(":service", ":api"), transitive[":app"])
    }

    @Test
    fun `changed dependency module affects dependents`() {
        val modules = GradleProjectScanner().scan(fixture("multi-module-deps").toString())
        val graph = ModuleDependencyGraph()

        val affected = graph.findAffectedModules(listOf("api/src/main/kotlin/com/example/api/ApiModel.kt"), modules, emptyList())

        assertEquals(listOf(":api", ":service", ":app"), affected)
    }

    private fun fixture(name: String): Path =
        Path.of(javaClass.classLoader.getResource("fixtures/jvm/$name")!!.toURI())
}
