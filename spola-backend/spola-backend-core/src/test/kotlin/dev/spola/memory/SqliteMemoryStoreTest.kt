package dev.spola.memory

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqliteMemoryStoreTest {

    @Test
    fun `save and search memory entry`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()
        val store = SqliteMemoryStore(dbPath)

        store.save("user_name", "Giona")
        val results = store.search("Giona")

        assertEquals(1, results.size)
        assertEquals("user_name", results[0].key)
        assertEquals("Giona", results[0].value)
    }

    @Test
    fun `save overwrites existing key`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()
        val store = SqliteMemoryStore(dbPath)

        store.save("key1", "value1")
        store.save("key1", "value2")

        val results = store.search("key1")
        assertEquals(1, results.size)
        assertEquals("value2", results[0].value)
    }

    @Test
    fun `search by key prefix`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()
        val store = SqliteMemoryStore(dbPath)

        store.save("pref_user", "Alice")
        store.save("pref_lang", "Kotlin")
        store.save("other_thing", "irrelevant")

        val results = store.search("pref_")
        assertEquals(2, results.size)
    }

    @Test
    fun `search by value content`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()
        val store = SqliteMemoryStore(dbPath)

        store.save("note", "User prefers tabs over spaces")
        val results = store.search("tabs")

        assertEquals(1, results.size)
        assertEquals("note", results[0].key)
    }

    @Test
    fun `delete removes entry`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()
        val store = SqliteMemoryStore(dbPath)

        store.save("todelete", "will be removed")
        assertTrue(store.delete("todelete"))
        assertFalse(store.delete("nonexistent"))
        assertEquals(0, store.search("todelete").size)
    }

    @Test
    fun `listAll returns all entries`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()
        val store = SqliteMemoryStore(dbPath)

        store.save("a", "1")
        store.save("b", "2")
        store.save("c", "3")

        val all = store.listAll()
        assertEquals(3, all.size)
    }

    @Test
    fun `multiple stores are isolated by path`(@TempDir tempDir: Path) = runTest {
        val db1 = SqliteMemoryStore(tempDir.resolve("db1.db").toString())
        val db2 = SqliteMemoryStore(tempDir.resolve("db2.db").toString())

        db1.save("key", "db1_value")
        db2.save("key", "db2_value")

        assertEquals("db1_value", db1.search("key")[0].value)
        assertEquals("db2_value", db2.search("key")[0].value)
    }
}
