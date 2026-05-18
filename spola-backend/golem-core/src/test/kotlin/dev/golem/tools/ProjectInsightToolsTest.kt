package dev.spola.tools

import dev.spola.ToolRegistry
import dev.spola.jvm.ProjectInsightStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

class ProjectInsightToolsTest {
    @Test
    fun `project insight save and search integration`(@TempDir tempDir: Path) = runTest {
        val registry = ToolRegistry()
        registerProjectInsightTools(registry, ProjectInsightStore(tempDir.resolve("insights.db").toString()))

        val save = registry.get("project_insight_save")!!.execute(
            mapOf("module" to ":app", "key" to "test-framework", "value" to "kotest"),
        )
        val search = registry.get("project_insight_search")!!.execute(mapOf("module" to ":app"))

        assertTrue(save.success, save.output)
        assertTrue(search.success, search.output)
        assertTrue(search.output.contains("kotest"))
    }

    @Test
    fun `project insight delete returns failure when missing`(@TempDir tempDir: Path) = runTest {
        val registry = ToolRegistry()
        registerProjectInsightTools(registry, ProjectInsightStore(tempDir.resolve("insights.db").toString()))

        val delete = registry.get("project_insight_delete")!!.execute(mapOf("key" to "missing"))

        assertTrue(!delete.success)
        assertTrue(delete.output.contains("not found"))
    }
}
