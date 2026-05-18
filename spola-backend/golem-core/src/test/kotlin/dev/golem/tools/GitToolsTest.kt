package dev.spola.tools

import dev.spola.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class GitToolsTest {

    @Test
    fun `git_status shows modified file`(@TempDir tempDir: Path) = runTest {
        initRepo(tempDir)
        val tracked = tempDir.resolve("tracked.txt")
        Files.writeString(tracked, "one\n")
        git(tempDir, "add", "tracked.txt")
        git(tempDir, "commit", "-m", "initial")
        Files.writeString(tracked, "one\ntwo\n")

        val registry = ToolRegistry()
        registerGitTools(registry)

        val result = withWorkingDirectory(tempDir) {
            registry.get("git_status")!!.execute(mapOf("path" to "tracked.txt"))
        }

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains("tracked.txt"), result.output)
        assertTrue(result.output.contains("M") || result.output.contains("??"), result.output)
    }

    @Test
    fun `git_commit succeeds and git_log returns commit`(@TempDir tempDir: Path) = runTest {
        initRepo(tempDir)
        Files.writeString(tempDir.resolve("note.txt"), "hello\n")

        val registry = ToolRegistry()
        registerGitTools(registry)

        val commitResult = withWorkingDirectory(tempDir) {
            registry.get("git_commit")!!.execute(mapOf("message" to "Add note"))
        }
        assertTrue(commitResult.success, commitResult.output)
        assertTrue(commitResult.output.contains("Add note") || commitResult.output.contains("1 file changed"), commitResult.output)

        val logResult = withWorkingDirectory(tempDir) {
            registry.get("git_log")!!.execute(mapOf("limit" to 1))
        }
        assertTrue(logResult.success, logResult.output)
        assertTrue(logResult.output.contains("Add note"), logResult.output)
    }

    @Test
    fun `git_diff shows file changes`(@TempDir tempDir: Path) = runTest {
        initRepo(tempDir)
        val tracked = tempDir.resolve("tracked.txt")
        Files.writeString(tracked, "before\n")
        git(tempDir, "add", "tracked.txt")
        git(tempDir, "commit", "-m", "initial")
        Files.writeString(tracked, "before\nafter\n")

        val registry = ToolRegistry()
        registerGitTools(registry)

        val result = withWorkingDirectory(tempDir) {
            registry.get("git_diff")!!.execute(mapOf("path" to "tracked.txt"))
        }

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains("tracked.txt"), result.output)
        assertTrue(result.output.contains("+after"), result.output)
    }

    @Test
    fun `git_diff with cached shows staged changes`(@TempDir tempDir: Path) = runTest {
        initRepo(tempDir)
        val f = tempDir.resolve("tracked.txt")
        Files.writeString(f, "one\n")
        git(tempDir, "add", "tracked.txt")
        git(tempDir, "commit", "-m", "init")
        Files.writeString(f, "one\ntwo\n")
        git(tempDir, "add", "tracked.txt")
        val registry = ToolRegistry()
        registerGitTools(registry)
        val result = withWorkingDirectory(tempDir) {
            registry.get("git_diff")!!.execute(mapOf("cached" to true, "path" to "tracked.txt"))
        }
        assertTrue(result.success, result.output)
        assertTrue(result.output.contains("+two"), result.output)
    }

    @Test
    fun `git_status rejects path traversal`(@TempDir tempDir: Path) = runTest {
        initRepo(tempDir)
        val registry = ToolRegistry()
        registerGitTools(registry)
        val result = withWorkingDirectory(tempDir) {
            registry.get("git_status")!!.execute(mapOf("path" to "../../etc/passwd"))
        }
        assertTrue(!result.success, "Expected failure for path traversal, got: ${result.output}")
    }

    private fun initRepo(repoDir: Path) {
        git(repoDir, "init")
        git(repoDir, "config", "user.name", "Golem Test")
        git(repoDir, "config", "user.email", "golem@example.com")
    }

    private fun git(repoDir: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repoDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }

    private suspend fun <T> withWorkingDirectory(path: Path, block: suspend () -> T): T {
        val previous = System.getProperty("user.dir")
        System.setProperty("user.dir", path.toString())
        return try {
            block()
        } finally {
            System.setProperty("user.dir", previous)
        }
    }
}
