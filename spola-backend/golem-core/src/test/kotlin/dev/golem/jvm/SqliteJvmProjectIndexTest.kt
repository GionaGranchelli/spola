package dev.spola.jvm

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteJvmProjectIndexTest {
    @Test
    fun `getSnapshot returns null before first scan`(@TempDir tempDir: Path) = runTest {
        val index = SqliteJvmProjectIndex(tempDir.resolve("index.db").toString())

        assertNull(index.getSnapshot())
    }

    @Test
    fun `scan stores snapshot and dependencies`(@TempDir tempDir: Path) = runTest {
        val index = SqliteJvmProjectIndex(tempDir.resolve("index.db").toString())
        val snapshot = index.scan(fixture("simple-multi-module").toString())

        assertEquals(3, snapshot.modules.size)
        val stored = index.getSnapshot()
        assertNotNull(stored)
        assertTrue(stored.modules.first { it.name == ":app" }.dependencies.any { it.contains(":lib") })
    }

    @Test
    fun `rescan overwrites old data`(@TempDir tempDir: Path) = runTest {
        val index = SqliteJvmProjectIndex(tempDir.resolve("index.db").toString())
        index.scan(fixture("simple-multi-module").toString())
        val snapshot = index.scan(fixture("single-module").toString())

        assertEquals(1, snapshot.modules.size)
        assertEquals(listOf(":"), index.getSnapshot()!!.modules.map { it.name })
    }

    @Test
    fun `scan indexes symbols`(@TempDir tempDir: Path) = runTest {
        val index = SqliteJvmProjectIndex(tempDir.resolve("index.db").toString())
        index.scan(fixture("simple-multi-module").toString())

        val symbols = index.searchSymbols("Helper", module = ":lib")

        assertEquals(1, symbols.size)
        assertEquals(SymbolKind.CLASS, symbols[0].kind)
    }

    private fun fixture(name: String): Path =
        Path.of(javaClass.classLoader.getResource("fixtures/jvm/$name")!!.toURI())
}
