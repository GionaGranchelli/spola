package dev.spola.checkpoint

import dev.spola.SystemMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class CheckpointDiffTest {

    @Test
    fun `diff is null when not in a git repo`(@TempDir tempDir: Path) {
        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val manager = CheckpointManager(store, workingDirectory = tempDir.toString())

        val id = manager.save("test-session", 1, listOf(SystemMessage("test")))
        assertTrue(id > 0)

        val loaded = store.loadById(id)
        assertNotNull(loaded)
        // Not a git repo, so diff should be null
        assertNull(loaded.diff, "Diff should be null when not in a git repo")

        store.close()
    }

    @Test
    fun `diff is stored when in a git repo with changes`(@TempDir tempDir: Path) {
        // Initialize a git repo
        val workDir = tempDir.toFile()
        gitInit(workDir)
        gitConfig(workDir)
        File(workDir, "initial.txt").writeText("initial content")
        gitAddAll(workDir)
        gitCommit(workDir, "initial commit")

        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val manager = CheckpointManager(store, workingDirectory = tempDir.toString())

        // Save a checkpoint — no changes yet
        val id1 = manager.save("test-session", 1, listOf(SystemMessage("turn 1")))
        val cp1 = store.loadById(id1)
        assertNotNull(cp1)
        // No unstaged changes (committed everything), so diff is empty string
        assertEquals("", cp1.diff, "Diff should be empty when no uncommitted changes")

        // Now make changes — modify existing tracked file
        File(workDir, "initial.txt").writeText("modified content")

        val id2 = manager.save("test-session", 2, listOf(SystemMessage("turn 2")))
        val cp2 = store.loadById(id2)
        assertNotNull(cp2)
        assertNotNull(cp2.diff, "Diff should not be null when there are changes")
        assertTrue(cp2.diff.contains("modified content"), "Diff should contain the modified content")
        // git diff HEAD only shows changes to tracked files, not untracked files

        store.close()
    }

    @Test
    fun `diff is empty string when no uncommitted changes`(@TempDir tempDir: Path) {
        val workDir = tempDir.toFile()
        gitInit(workDir)
        gitConfig(workDir)
        File(workDir, "readme.md").writeText("# Test")
        gitAddAll(workDir)
        gitCommit(workDir, "initial")

        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val manager = CheckpointManager(store, workingDirectory = tempDir.toString())

        val id = manager.save("test-session", 1, listOf(SystemMessage("no changes")))
        val cp = store.loadById(id)
        assertNotNull(cp)
        assertEquals("", cp.diff, "Diff should be empty string when no uncommitted changes")

        store.close()
    }

    @Test
    fun `diff is truncated to 50KB`(@TempDir tempDir: Path) {
        val workDir = tempDir.toFile()
        gitInit(workDir)
        gitConfig(workDir)
        // Create and commit a small file
        File(workDir, "large.txt").writeText("initial")
        gitAddAll(workDir)
        gitCommit(workDir, "initial")

        // Modify the file with content large enough to produce >50KB diff
        File(workDir, "large.txt").writeText("A".repeat(60_000))

        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val manager = CheckpointManager(store, workingDirectory = tempDir.toString())

        val id = manager.save("test-session", 1, listOf(SystemMessage("large diff")))
        val cp = store.loadById(id)
        assertNotNull(cp)
        assertNotNull(cp.diff)
        assertTrue(cp.diff.length <= 50 * 1024, "Diff should be truncated to 50KB, got ${cp.diff.length}")

        store.close()
    }

    @Test
    fun `loadDiff returns null for non-existent checkpoint`(@TempDir tempDir: Path) {
        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val manager = CheckpointManager(store, workingDirectory = tempDir.toString())

        val diff = manager.loadDiff(99999)
        assertNull(diff, "loadDiff should return null for non-existent checkpoint id")

        store.close()
    }

    @Test
    fun `loadDiff returns diff for existing checkpoint`(@TempDir tempDir: Path) {
        val workDir = tempDir.toFile()
        gitInit(workDir)
        gitConfig(workDir)
        File(workDir, "file.txt").writeText("initial")
        gitAddAll(workDir)
        gitCommit(workDir, "initial")
        File(workDir, "file.txt").writeText("modified")

        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val manager = CheckpointManager(store, workingDirectory = tempDir.toString())

        val id = manager.save("test-session", 1, listOf(SystemMessage("test")))
        val diff = manager.loadDiff(id)
        assertNotNull(diff)
        assertTrue(diff.contains("modified"), "Diff should reference the modified content")

        store.close()
    }

    @Test
    fun `checkpoint diff survives save and reload cycle`(@TempDir tempDir: Path) {
        val workDir = tempDir.toFile()
        gitInit(workDir)
        gitConfig(workDir)
        File(workDir, "data.txt").writeText("original")
        gitAddAll(workDir)
        gitCommit(workDir, "initial")

        // Make changes and save checkpoint
        File(workDir, "data.txt").writeText("updated content")
        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val manager = CheckpointManager(store, workingDirectory = tempDir.toString())
        val id = manager.save("test-session", 1, listOf(SystemMessage("save cycle")))
        store.close()

        // Reload from a fresh store
        val store2 = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val cp = store2.loadById(id)
        assertNotNull(cp)
        assertNotNull(cp.diff)
        assertTrue(cp.diff.contains("updated content"))
        store2.close()
    }

    // --- Git helpers ---

    private fun gitInit(dir: File) {
        val pb = ProcessBuilder("git", "init")
            .directory(dir)
            .redirectErrorStream(true)
        val p = pb.start()
        assertEquals(0, p.waitFor(), "git init failed: ${p.inputStream.bufferedReader().readText()}")
    }

    private fun gitConfig(dir: File) {
        // Set minimal git config to avoid errors
        runProcess(dir, "git", "config", "user.email", "test@test.com")
        runProcess(dir, "git", "config", "user.name", "Test User")
    }

    private fun gitAddAll(dir: File) {
        runProcess(dir, "git", "add", ".")
    }

    private fun gitCommit(dir: File, message: String) {
        runProcess(dir, "git", "commit", "-m", message)
    }

    private fun runProcess(dir: File, vararg cmd: String): String {
        val pb = ProcessBuilder(*cmd)
            .directory(dir)
            .redirectErrorStream(true)
        val p = pb.start()
        val output = p.inputStream.bufferedReader().readText()
        val exit = p.waitFor()
        assertEquals(0, exit, "Command '${cmd.joinToString(" ")}' failed: $output")
        return output
    }
}
