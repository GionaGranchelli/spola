package dev.spola.jvm

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmIndexCoordinatorTest {
    @Test
    fun `ensureFresh performs initial scan when no snapshot exists`(@TempDir tempDir: Path) = runTest {
        val project = fixture("simple-multi-module")
        val index = SqliteJvmProjectIndex(tempDir.resolve("index.db").toString())
        val coordinator = JvmIndexCoordinator(autoRefresh = false) { project.toString() }

        val snapshot = coordinator.ensureFresh(IndexFreshnessPolicy.QUERY_PROJECT_OVERVIEW, index)

        assertEquals(project.toAbsolutePath().normalize().toString(), snapshot.projectDir)
        assertTrue(snapshot.modules.isNotEmpty())
    }

    @Test
    fun `ensureFresh rescans when stale and auto refresh is disabled`(@TempDir tempDir: Path) = runTest {
        val project = fixture("simple-multi-module")
        val tempProject = tempDir.resolve("project")
        Files.walk(project).use { paths ->
            paths.forEach { source ->
                val target = tempProject.resolve(project.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target)
                else Files.copy(source, target)
            }
        }
        val index = SqliteJvmProjectIndex(tempDir.resolve("index.db").toString())
        val first = index.scan(tempProject.toString())
        val coordinator = JvmIndexCoordinator(
            freshnessPolicy = IndexFreshnessPolicy(
                defaults = mapOf(IndexFreshnessPolicy.QUERY_SYMBOL_SEARCH to FreshnessPolicy(maxAgeMs = 0L)),
            ),
            autoRefresh = false,
        ) { tempProject.toString() }

        val utilsFile = tempProject.resolve("lib/src/main/kotlin/com/example/lib/Utils.kt")
        Files.writeString(utilsFile, Files.readString(utilsFile).replace("object Utils", "object Helpers"))

        val refreshed = coordinator.ensureFresh(IndexFreshnessPolicy.QUERY_SYMBOL_SEARCH, index)
        val symbols = index.searchSymbols("Helpers")

        assertTrue(refreshed.scannedAt >= first.scannedAt)
        assertTrue(symbols.any { it.name == "Helpers" })
    }

    @Test
    fun `refreshChangedPaths does incremental reindex for source file changes`(@TempDir tempDir: Path) = runTest {
        val project = fixture("simple-multi-module")
        val tempProject = tempDir.resolve("project")
        Files.walk(project).use { paths ->
            paths.forEach { source ->
                val target = tempProject.resolve(project.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target)
                else Files.copy(source, target)
            }
        }
        val index = SqliteJvmProjectIndex(tempDir.resolve("index.db").toString())
        val first = index.scan(tempProject.toString())
        val firstScannedAt = first.scannedAt

        val utilsFile = tempProject.resolve("lib/src/main/kotlin/com/example/lib/Utils.kt")
        Files.writeString(utilsFile, Files.readString(utilsFile).replace("object Utils", "object MyUtils"))

        val refreshed = index.refreshChangedPaths(tempProject.toString(), listOf(utilsFile))
        val symbols = index.searchSymbols("MyUtils")

        assertTrue(refreshed.scannedAt > firstScannedAt, "scannedAt must advance after refresh")
        assertTrue(symbols.any { it.name == "MyUtils" }, "Updated symbol MyUtils must be indexed")
    }

    @Test
    fun `refreshChangedPaths triggers full rescan for build file changes`(@TempDir tempDir: Path) = runTest {
        val project = fixture("simple-multi-module")
        val tempProject = tempDir.resolve("project")
        Files.walk(project).use { paths ->
            paths.forEach { source ->
                val target = tempProject.resolve(project.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target)
                else Files.copy(source, target)
            }
        }
        val index = SqliteJvmProjectIndex(tempDir.resolve("index.db").toString())
        val first = index.scan(tempProject.toString())
        val firstScannedAt = first.scannedAt

        val buildFile = tempProject.resolve("lib/build.gradle.kts")
        Files.writeString(buildFile, Files.readString(buildFile) + "\n// touched\n")

        val refreshed = index.refreshChangedPaths(tempProject.toString(), listOf(buildFile))

        assertTrue(refreshed.scannedAt > firstScannedAt, "scannedAt must advance after build file change")
    }

    @Test
    fun `refreshChangedPaths handles deleted source files`(@TempDir tempDir: Path) = runTest {
        val project = fixture("simple-multi-module")
        val tempProject = tempDir.resolve("project")
        Files.walk(project).use { paths ->
            paths.forEach { source ->
                val target = tempProject.resolve(project.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target)
                else Files.copy(source, target)
            }
        }
        val index = SqliteJvmProjectIndex(tempDir.resolve("index.db").toString())
        val first = index.scan(tempProject.toString())

        val deletedFile = tempProject.resolve("lib/src/main/kotlin/com/example/lib/Utils.kt")
        Files.delete(deletedFile)

        val refreshed = index.refreshChangedPaths(tempProject.toString(), listOf(deletedFile))

        assertTrue(refreshed.scannedAt >= first.scannedAt, "scannedAt must not regress after delete")
        val symbols = index.searchSymbols("Utils")
        assertTrue(symbols.none { it.file.contains("Utils") }, "Deleted file symbols must be removed")
    }

    private fun fixture(name: String): Path =
        Path.of(javaClass.classLoader.getResource("fixtures/jvm/$name")!!.toURI())
}
