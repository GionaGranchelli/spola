package dev.spola.compression

import dev.spola.ToolResult
import dev.spola.maybeCompressToolResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenJuiceTest {

    @Test
    fun `small output with no matching rules passes through unchanged`() {
        val result = TokenJuice.compact("unknown_tool", "hello world")
        assertEquals("hello world", result)
    }

    @Test
    fun `strip ANSI escape sequences`() {
        val input = "${27.toChar()}[31mred${27.toChar()}[0m"
        val result = TokenJuice.compact("shell", input)
        assertTrue(result.contains("red"))
    }

    @Test
    fun `dedup consecutive duplicate lines`() {
        val result = TokenJuice.compact("shell", "a\na\nb\nb\nc")
        val lines = result.lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size, "Expected 3 unique lines, got: $lines")
        assertEquals("a", lines[0])
        assertEquals("b", lines[1])
        assertEquals("c", lines[2])
    }

    @Test
    fun `smart truncate long output`() {
        val text = "x".repeat(10000)
        val result = TokenJuice.compact("read_file", text)
        assertTrue(result.length < text.length, "Expected compressed, got ${result.length} vs $text.length")
        assertTrue(result.contains("truncated"))
    }

    @Test
    fun `git tools get stats compression`() {
        val result = TokenJuice.compact("git_diff", "100 files changed, 500 insertions(+), 200 deletions(-)\nmore details...")
        assertTrue(result.contains("100 files changed") || result.contains("insertions"))
    }

    @Test
    fun `disabled compression returns original`() {
        val text = "x".repeat(10000)
        val result = TokenJuice.compact("shell", text, enabled = false)
        assertEquals(text, result)
    }

    @Test
    fun `search files gets grouped output`() {
        val text = """
            src/main.kt:10: val x = 1
            src/main.kt:20: val y = 2
            src/utils.kt:5: fun help
        """.trimIndent()
        val result = TokenJuice.compact("search_files", text)
        assertTrue(result.contains("src/main.kt") || result.contains("src/utils.kt"))
    }

    @Test
    fun `glob pattern trailing star matches prefix`() {
        assertTrue("shell".matches(TokenJuice.toolPatternToRegex("shell*")))
        assertTrue("shell_v2".matches(TokenJuice.toolPatternToRegex("shell*")))
        assertTrue(!"xshell".matches(TokenJuice.toolPatternToRegex("shell*")))
    }

    @Test
    fun `glob pattern escapes regex metacharacters`() {
        // '.' should be treated literally, not as "any char"
        assertTrue("a.b".matches(TokenJuice.toolPatternToRegex("a.b")))
        assertTrue(!"axb".matches(TokenJuice.toolPatternToRegex("a.b")))
    }

    @Test
    fun `glob pattern single star matches everything`() {
        assertTrue("anything".matches(TokenJuice.toolPatternToRegex("*")))
        assertTrue("".matches(TokenJuice.toolPatternToRegex("*")))
    }

    @Test
    fun `no matching rules passes through unchanged`() {
        val text = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk\nl\nm\nn\no\np\n".repeat(200)
        // Without matching rules, no strategies are applied — output is used as-is
        val result = TokenJuice.compact("unknown_tool", text)
        assertEquals(text, result)
    }

    @Test
    fun `compression footer is skipped when it makes output longer`() {
        val original = "${27.toChar()}[1;3m${"a".repeat(40)}${27.toChar()}[0m"
        val result = maybeCompressToolResult(
            toolName = "shell",
            result = ToolResult.ok(original),
            compressionEnabled = true,
        )

        assertEquals(50, original.length)
        assertEquals(original, result.output)
        assertFalse(result.output.contains("[TokenJuice:"))
    }
}
