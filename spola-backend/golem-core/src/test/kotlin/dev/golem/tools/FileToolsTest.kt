package dev.spola.tools

import dev.spola.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileToolsTest {

    @Test
    fun `read_file returns file contents with line numbers`(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("test.txt")
        Files.writeString(file, "line1\nline2\nline3\n")

        val registry = ToolRegistry()
        registerFileTools(registry)

        val tool = registry.get("read_file")!!
        val result = tool.execute(mapOf("path" to file.toString()))

        assertTrue(result.success, "Expected success, got: ${result.error}")
        assertTrue(result.output.contains("1|line1"))
        assertTrue(result.output.contains("2|line2"))
        assertTrue(result.output.contains("3|line3"))
    }

    @Test
    fun `read_file returns error for nonexistent file`(@TempDir tempDir: Path) = runTest {
        val registry = ToolRegistry()
        registerFileTools(registry)

        val tool = registry.get("read_file")!!
        val result = tool.execute(mapOf("path" to tempDir.resolve("nonexistent.txt").toString()))

        assertFalse(result.success)
        assertTrue(result.output.contains("not found", ignoreCase = true) || result.error?.contains("not found", ignoreCase = true) == true)
    }

    @Test
    fun `read_file respects offset and limit`(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("test.txt")
        Files.writeString(file, (1..100).joinToString("\n") { "line$it" })

        val registry = ToolRegistry()
        registerFileTools(registry)

        val tool = registry.get("read_file")!!
        val result = tool.execute(mapOf(
            "path" to file.toString(),
            "offset" to 10,
            "limit" to 5,
        ))

        assertTrue(result.success)
        assertTrue(result.output.contains("10|line10"))
        assertTrue(result.output.contains("14|line14"))
        assertFalse(result.output.contains("15|line15")) // limit stops at 14
    }

    @Test
    fun `write_file creates new file with correct content`(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("new_file.txt")
        val registry = ToolRegistry()
        registerFileTools(registry)

        val tool = registry.get("write_file")!!
        val result = tool.execute(mapOf(
            "path" to file.toString(),
            "content" to "hello golem",
        ))

        assertTrue(result.success)
        assertTrue(Files.exists(file))
        assertEquals("hello golem", Files.readString(file))
    }

    @Test
    fun `write_file overwrites existing file`(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("existing.txt")
        Files.writeString(file, "old content")

        val registry = ToolRegistry()
        registerFileTools(registry)

        val tool = registry.get("write_file")!!
        tool.execute(mapOf(
            "path" to file.toString(),
            "content" to "new content",
        ))

        assertEquals("new content", Files.readString(file))
    }

    @Test
    fun `write_file creates parent directories`(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("deep/nested/dir/file.txt")
        val registry = ToolRegistry()
        registerFileTools(registry)

        val tool = registry.get("write_file")!!
        val result = tool.execute(mapOf(
            "path" to file.toString(),
            "content" to "nested content",
        ))

        assertTrue(result.success)
        assertTrue(Files.exists(file))
        assertEquals("nested content", Files.readString(file))
    }

    @Test
    fun `search_files finds matching content`(@TempDir tempDir: Path) = runTest {
        Files.writeString(tempDir.resolve("a.txt"), "hello world\nfoo bar")
        Files.writeString(tempDir.resolve("b.txt"), "goodbye world\nbaz qux")

        val registry = ToolRegistry()
        registerFileTools(registry)

        val tool = registry.get("search_files")!!
        val result = tool.execute(mapOf(
            "pattern" to "hello",
            "path" to tempDir.toString(),
        ))

        assertTrue(result.success, "Expected success, got: ${result.error}")
        assertTrue(result.output.contains("a.txt"))
        assertFalse(result.output.contains("b.txt"))
    }

    @Test
    fun `search_files respects file glob`(@TempDir tempDir: Path) = runTest {
        Files.writeString(tempDir.resolve("data.txt"), "secret content")
        Files.writeString(tempDir.resolve("data.log"), "secret content")

        val registry = ToolRegistry()
        registerFileTools(registry)

        val tool = registry.get("search_files")!!
        val result = tool.execute(mapOf(
            "pattern" to "secret",
            "path" to tempDir.toString(),
            "file_glob" to "*.txt",
        ))

        assertTrue(result.success)
        assertTrue(result.output.contains("data.txt"))
        assertFalse(result.output.contains("data.log"))
    }

    @Test
    fun `search_files returns no matches for nonexistent pattern`(@TempDir tempDir: Path) = runTest {
        Files.writeString(tempDir.resolve("data.txt"), "hello")

        val registry = ToolRegistry()
        registerFileTools(registry)

        val tool = registry.get("search_files")!!
        val result = tool.execute(mapOf(
            "pattern" to "ZZZZ_NOTHING",
            "path" to tempDir.toString(),
        ))

        assertTrue(result.success)
        assertTrue(result.output.contains("No matches"))
    }
}
