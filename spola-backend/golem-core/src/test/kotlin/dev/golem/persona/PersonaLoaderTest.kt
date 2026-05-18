package dev.spola.persona

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class PersonaLoaderTest {

    @Test
    fun `load returns default persona when no files exist`(@TempDir tempDir: Path) {
        val persona = PersonaLoader.load(workingDirectory = tempDir.toString())
        assertTrue(persona.contains("Golem"), "Default persona should mention Golem")
        assertTrue(persona.contains("JVM"), "Default persona should mention JVM")
        assertTrue(persona.contains("read_file"), "Default persona should list available tools")
    }

    @Test
    fun `load returns AGENTS_md content when present`(@TempDir tempDir: Path) {
        val agentsMd = tempDir.resolve("AGENTS.md")
        Files.writeString(agentsMd, "Custom AGENTS.md persona")

        val persona = PersonaLoader.load(workingDirectory = tempDir.toString())
        assertTrue(persona.contains("Custom AGENTS.md persona"))
    }

    @Test
    fun `load falls back to CLAUDE_md when AGENTS_md absent`(@TempDir tempDir: Path) {
        val claudeMd = tempDir.resolve("CLAUDE.md")
        Files.writeString(claudeMd, "Custom CLAUDE.md persona")

        val persona = PersonaLoader.load(workingDirectory = tempDir.toString())
        assertTrue(persona.contains("Custom CLAUDE.md persona"))
    }

    @Test
    fun `explicit path overrides file discovery`(@TempDir tempDir: Path) {
        val agentsMd = tempDir.resolve("AGENTS.md")
        Files.writeString(agentsMd, "AGENTS.md content")

        val customFile = tempDir.resolve("custom-persona.md")
        Files.writeString(customFile, "Explicit persona content")

        val persona = PersonaLoader.load(
            explicitPath = customFile.toString(),
            workingDirectory = tempDir.toString(),
        )
        assertTrue(persona.contains("Explicit persona content"))
        assertTrue(!persona.contains("AGENTS.md content"))
    }

    @Test
    fun `explicit path to nonexistent file falls back`(@TempDir tempDir: Path) {
        val agentsMd = tempDir.resolve("AGENTS.md")
        Files.writeString(agentsMd, "AGENTS.md content")

        val persona = PersonaLoader.load(
            explicitPath = tempDir.resolve("nonexistent.md").toString(),
            workingDirectory = tempDir.toString(),
        )
        assertTrue(persona.contains("AGENTS.md content"),
            "Should fall back to default/AGENTS.md when explicit path doesn't exist")
    }
}
