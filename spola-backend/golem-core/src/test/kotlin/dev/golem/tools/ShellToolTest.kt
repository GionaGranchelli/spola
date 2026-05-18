package dev.spola.tools

import dev.spola.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellToolTest {

    @Test
    fun `shell executes command and returns stdout`(@TempDir tempDir: Path) = runTest {
        val registry = ToolRegistry()
        registerShellTool(registry)

        val tool = registry.get("shell")!!
        val result = tool.execute(mapOf(
            "command" to "echo hello golem",
            "workdir" to tempDir.toString(),
            "timeout" to 10,
        ))

        assertTrue(result.success, "Expected success, got: ${result.error}")
        assertTrue(result.output.contains("hello golem"), "Output: ${result.output}")
    }

    @Test
    fun `shell returns error on non-zero exit`(@TempDir tempDir: Path) = runTest {
        val registry = ToolRegistry()
        registerShellTool(registry)

        val tool = registry.get("shell")!!
        val result = tool.execute(mapOf(
            "command" to "ls /nonexistent_path_xyz_12345",
            "workdir" to tempDir.toString(),
            "timeout" to 10,
        ))

        assertTrue(!result.success, "Expected failure, but got success with output: ${result.output}")
    }

    @Test
    fun `shell enforces timeout`() = runTest {
        val registry = ToolRegistry()
        registerShellTool(registry)

        val tool = registry.get("shell")!!
        val result = tool.execute(mapOf(
            "command" to "sleep 10",
            "workdir" to ".",
            "timeout" to 1,
        ))

        assertTrue(!result.success, "Expected timeout failure, but got success with: ${result.output}")
        val output = result.output.lowercase()
        assertTrue(
            output.contains("timed out") || output.contains("timeout") || output.contains("exit code"),
            "Expected timeout-related message, got: ${result.output}"
        )
    }

    @Test
    fun `shell works with absolute path`(@TempDir tempDir: Path) = runTest {
        Files.writeString(tempDir.resolve("hello.txt"), "hello golem")

        val registry = ToolRegistry()
        registerShellTool(registry)

        val tool = registry.get("shell")!!
        val result = tool.execute(mapOf(
            "command" to "cat ${tempDir.resolve("hello.txt")}",
            "workdir" to ".",
            "timeout" to 10,
        ))

        assertTrue(result.success, "Expected success, got: ${result.error}")
        assertTrue(result.output.contains("hello golem"), "Output: ${result.output}")
    }

    @Test
    fun `shell blocks user provided rtk wrapper`() = runTest {
        val registry = ToolRegistry()
        registerShellTool(registry)

        val tool = registry.get("shell")!!
        val result = tool.execute(mapOf(
            "command" to "rtk sudo rm -rf /",
            "workdir" to ".",
            "timeout" to 10,
        ))

        assertFalse(result.success, "Expected failure, got: ${result.output}")
        assertTrue(result.output.contains("Direct 'rtk' command is not allowed"), "Output: ${result.output}")
    }

    @Test
    fun `shell blocks user provided rtk path variant`() = runTest {
        val registry = ToolRegistry()
        registerShellTool(registry)

        val tool = registry.get("shell")!!
        val result = tool.execute(mapOf(
            "command" to "/usr/bin/rtk whoami",
            "workdir" to ".",
            "timeout" to 10,
        ))

        assertFalse(result.success, "Expected failure, got: ${result.output}")
        assertTrue(result.output.contains("Direct 'rtk' command is not allowed"), "Output: ${result.output}")
    }

    @Test
    fun `shell runs supported command transparently with or without rtk`(@TempDir tempDir: Path) = runTest {
        val registry = ToolRegistry()
        registerShellTool(registry)
        val tool = registry.get("shell")!!

        tool.execute(mapOf(
            "command" to "git init",
            "workdir" to tempDir.toString(),
            "timeout" to 10,
        ))

        val result = tool.execute(mapOf(
            "command" to "git status --short",
            "workdir" to tempDir.toString(),
            "timeout" to 10,
        ))

        assertTrue(result.success, "Expected success, got: ${result.output}")
    }

    @Test
    fun `shell leaves unsupported commands unwrapped`() = runTest {
        val registry = ToolRegistry()
        registerShellTool(registry)

        val tool = registry.get("shell")!!
        val result = tool.execute(mapOf(
            "command" to "unsupported_cmd foo",
            "workdir" to ".",
            "timeout" to 10,
        ))

        assertFalse(result.success, "Expected command launch failure")
        assertFalse(
            result.output.contains("Direct 'rtk' command is not allowed"),
            "Unexpected RTK block message: ${result.output}"
        )
        assertFalse(
            result.output.contains("Command blocked for security"),
            "Unexpected security block message: ${result.output}"
        )
    }
}
