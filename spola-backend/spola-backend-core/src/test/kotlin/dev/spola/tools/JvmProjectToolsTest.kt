package dev.spola.tools

import dev.spola.ToolRegistry
import dev.spola.jvm.SqliteJvmProjectIndex
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

class JvmProjectToolsTest {
    @Test
    fun `project overview returns module list`(@TempDir tempDir: Path) = runWithProject(tempDir) { registry ->
        val result = registry.get("jvm_project_overview")!!.execute(emptyMap())

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains(":app"))
        assertTrue(result.output.contains(":lib"))
    }

    @Test
    fun `symbol search finds by name`(@TempDir tempDir: Path) = runWithProject(tempDir) { registry ->
        val result = registry.get("jvm_symbol_search")!!.execute(mapOf("name" to "Helper"))

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains("CLASS Helper"))
    }

    @Test
    fun `symbol search filters by module`(@TempDir tempDir: Path) = runWithProject(tempDir) { registry ->
        val result = registry.get("jvm_symbol_search")!!.execute(mapOf("name" to "App", "module" to ":app"))

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains(":app CLASS App"))
    }

    @Test
    fun `file outline returns file symbols`(@TempDir tempDir: Path) = runWithProject(tempDir) { registry ->
        val result = registry.get("jvm_file_outline")!!.execute(mapOf("path" to "lib/src/main/kotlin/com/example/lib/Utils.kt"))

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains("INTERFACE Formatter"))
        assertTrue(result.output.contains("OBJECT Utils"))
    }

    @Test
    fun `context pack returns compact output`(@TempDir tempDir: Path) = runWithProject(tempDir) { registry ->
        val result = registry.get("jvm_context_pack")!!.execute(mapOf("goal" to "Helper formatter"))

        assertTrue(result.success, result.output)
        assertTrue(result.output.length <= 2000)
        assertTrue(result.output.contains("Modules:"))
        assertTrue(result.output.contains("Helper"))
    }

    @Test
    fun `dependency trace returns module graph`(@TempDir tempDir: Path) = runWithProject(tempDir, "multi-module-deps") { registry ->
        val result = registry.get("jvm_dependency_trace")!!.execute(mapOf("module" to ":app"))

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains("implementation: :service"))
        assertTrue(result.output.contains("transitive: :service, :api"))
    }

    @Test
    fun `task suggest returns gradle command`(@TempDir tempDir: Path) = runWithProject(tempDir) { registry ->
        val result = registry.get("jvm_task_suggest")!!.execute(mapOf("module" to ":app", "change_type" to "src"))

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains("./gradlew :app:compileKotlin"))
    }

    @Test
    fun `failure explain parses output`(@TempDir tempDir: Path) = runWithProject(tempDir) { registry ->
        val output = """
            > Task :app:compileKotlin FAILED
            e: app/src/main/kotlin/com/example/App.kt:7:9 Unresolved reference 'Helper'.
        """.trimIndent()

        val result = registry.get("jvm_failure_explain")!!.execute(mapOf("output" to output))

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains("likely root cause"))
        assertTrue(result.output.contains(":app"))
    }

    @Test
    fun `verify plan suggests scoped commands`(@TempDir tempDir: Path) = runWithProject(tempDir, "multi-module-deps") { registry ->
        val result = registry.get("jvm_verify_plan")!!.execute(mapOf("paths" to "service/src/main/kotlin/com/example/service/Service.kt"))

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains(":service:compileKotlin"))
        assertTrue(result.output.contains(":app:test"))
    }

    private fun runWithProject(tempDir: Path, fixtureName: String = "simple-multi-module", block: suspend (ToolRegistry) -> Unit) = runTest {
        val originalUserDir = System.getProperty("user.dir")
        val project = fixture(fixtureName)
        try {
            System.setProperty("user.dir", project.toString())
            val index = SqliteJvmProjectIndex(tempDir.resolve("index.db").toString())
            val registry = ToolRegistry()
            registerJvmTools(registry, index)
            block(registry)
        } finally {
            System.setProperty("user.dir", originalUserDir)
        }
    }

    private fun fixture(name: String): Path =
        Path.of(javaClass.classLoader.getResource("fixtures/jvm/$name")!!.toURI())
}
