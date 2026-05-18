package dev.spola.tools

import dev.spola.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class EditToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var registry: ToolRegistry
    private lateinit var testFile: Path

    @BeforeEach
    fun setUp() {
        registry = ToolRegistry()
        registerFileTools(registry)
        registerEditTool(registry)
        testFile = tempDir.resolve("test.txt")
        System.setProperty("user.dir", tempDir.toString())
        // Don't set GOLEM_ALLOWED_DIRS — empty = all paths allowed
    }

    @Test
    fun `edit_file replaces single occurrence`() = runBlocking {
        val tool = registry.get("edit_file")!!
        Files.writeString(testFile, "Hello world\nThis is a test\n")
        val result = tool.execute(
            mapOf(
                "path" to testFile.toString(),
                "oldText" to "world",
                "newText" to "golem"
            )
        )
        assertTrue(result.success)
        assertEquals("Hello golem\nThis is a test\n", Files.readString(testFile))
        assertTrue(result.output.contains("- Hello world"))
        assertTrue(result.output.contains("+ Hello golem"))
    }

    @Test
    fun `edit_file with ambiguous match fails`() = runBlocking {
        val tool = registry.get("edit_file")!!
        Files.writeString(testFile, "Hello world\nThis is a world test\n")
        val result = tool.execute(
            mapOf(
                "path" to testFile.toString(),
                "oldText" to "world",
                "newText" to "golem"
            )
        )
        assertFalse(result.success)
        assertEquals("Found 2 occurrences of oldText. Use replaceAll=true to replace all or be more specific.", result.output)
    }

    @Test
    fun `edit_file with replaceAll replaces all`() = runBlocking {
        val tool = registry.get("edit_file")!!
        Files.writeString(testFile, "Hello world\nThis is a world test\n")
        val result = tool.execute(
            mapOf(
                "path" to testFile.toString(),
                "oldText" to "world",
                "newText" to "golem",
                "replaceAll" to true
            )
        )
        assertTrue(result.success)
        assertEquals("Hello golem\nThis is a golem test\n", Files.readString(testFile))
        assertTrue(result.output.contains("- Hello world"))
        assertTrue(result.output.contains("+ Hello golem"))
    }

    @Test
    fun `edit_file with non-existent text fails`() = runBlocking {
        val tool = registry.get("edit_file")!!
        Files.writeString(testFile, "Hello world\nThis is a test\n")
        val result = tool.execute(
            mapOf(
                "path" to testFile.toString(),
                "oldText" to "non-existent",
                "newText" to "golem"
            )
        )
        assertFalse(result.success)
        assertEquals("Text not found: oldText was not found in the file.", result.output)
    }

    @Test
    fun `edit_file with non-existent file fails`() = runBlocking {
        val tool = registry.get("edit_file")!!
        val result = tool.execute(
            mapOf(
                "path" to "non-existent-file.txt",
                "oldText" to "foo",
                "newText" to "bar"
            )
        )
        assertFalse(result.success)
        assertTrue(result.output.contains("File not found"))
}

}
