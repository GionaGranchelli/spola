package dev.spola.persona

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Loads the agent persona from an AGENTS.md or CLAUDE.md file.
 */
object PersonaLoader {

    private val defaultPersona = """
You are Golem, a JVM-based autonomous coding agent.
You help users build, debug, and understand Java/Kotlin projects.
You have access to tools: read, write, search files, run shell commands,
and maintain memory across sessions. Stored memories are automatically
injected into your context at the start of each session.

Guidelines:
- Be concise and precise
- Verify your work: after writing files, suggest compilation or test runs
- Use shell for builds, tests, git operations
- Use read_file for understanding existing code
- Use write_file for creating or modifying code
- Use memory_search to find more specific facts and memory_save to store new ones
- When the task is done, provide a clear summary of what was accomplished
""".trimIndent()

    /**
     * Load the persona from a file or return the default.
     *
     * Priority:
     * 1. Explicit path from --persona flag
     * 2. AGENTS.md in working directory
     * 3. CLAUDE.md in working directory
     * 4. Default persona
     */
    fun load(explicitPath: String? = null, workingDirectory: String = "."): String {
        if (explicitPath != null) {
            val path = Paths.get(explicitPath)
            if (Files.exists(path)) {
                return Files.readString(path).trim()
            }
        }

        val wd = Paths.get(workingDirectory).toAbsolutePath()

        val agentsMd = wd.resolve("AGENTS.md")
        if (Files.exists(agentsMd)) {
            return Files.readString(agentsMd).trim()
        }

        val claudeMd = wd.resolve("CLAUDE.md")
        if (Files.exists(claudeMd)) {
            return Files.readString(claudeMd).trim()
        }

        return defaultPersona
    }
}
