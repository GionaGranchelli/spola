package dev.spola.tools

import dev.spola.AssistantMessage
import dev.spola.ToolCall
import dev.spola.ToolRegistry
import dev.spola.ToolResultMessage
import dev.spola.UserMessage
import dev.spola.checkpoint.CheckpointManager
import dev.spola.checkpoint.CheckpointStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

class ProvenanceToolsTest {
    @Test
    fun `provenance export returns json bundle`(@TempDir tempDir: Path) = runTest {
        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val manager = CheckpointManager(store, workingDirectory = tempDir.toString())
        manager.save(
            sessionId = "session-1",
            turn = 1,
            conversation = listOf(
                UserMessage("run tests"),
                AssistantMessage("", listOf(ToolCall("1", "shell", mapOf("command" to "./gradlew test")))),
                ToolResultMessage("1", "shell", "Tests passed"),
            ),
        )
        val registry = ToolRegistry()
        registerProvenanceTools(registry, manager)

        val result = registry.get("provenance_export")!!.execute(mapOf("sessionId" to "session-1", "format" to "json"))

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains("\"sessionId\": \"session-1\""))
        assertTrue(result.output.contains("\"toolCalls\""))
    }

    @Test
    fun `provenance info summarizes bundle`(@TempDir tempDir: Path) = runTest {
        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val manager = CheckpointManager(store, workingDirectory = tempDir.toString())
        manager.saveRaw("session-2", 1, "[]")
        val registry = ToolRegistry()
        registerProvenanceTools(registry, manager)

        val result = registry.get("provenance_info")!!.execute(mapOf("bundleId" to "session-2"))

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains("sessionId=session-2"))
    }
}
