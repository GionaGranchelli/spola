package dev.spola.checkpoint

import dev.spola.AssistantMessage
import dev.spola.SystemMessage
import dev.spola.ToolCall
import dev.spola.ToolResultMessage
import dev.spola.UserMessage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckpointStoreTest {

    @Test
    fun `save and load checkpoint`(@TempDir tempDir: Path) {
        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val sessionId = "test-session-1"

        store.save(sessionId, 1, """[{"role":"system","content":"You are a helpful assistant"}]""")

        val loaded = store.load(sessionId)
        assertNotNull(loaded)
        assertEquals(sessionId, loaded.sessionId)
        assertEquals(1, loaded.turnNumber)
        assertTrue(loaded.conversationJson.contains("helpful assistant"))
        assertNotNull(loaded.createdAt)
        store.close()
    }

    @Test
    fun `load returns null for missing session`(@TempDir tempDir: Path) {
        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())

        val loaded = store.load("nonexistent-session")
        assertNull(loaded)
        store.close()
    }

    @Test
    fun `multiple checkpoints for session returns most recent`(@TempDir tempDir: Path) {
        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val sessionId = "multi-turn-session"

        store.save(sessionId, 1, """[{"role":"user","content":"hello"}]""")
        store.save(sessionId, 2, """[{"role":"user","content":"hello"},{"role":"assistant","content":"hi"}]""")
        store.save(sessionId, 3, """[{"role":"user","content":"hello"},{"role":"assistant","content":"hi"},{"role":"user","content":"bye"}]""")

        val loaded = store.load(sessionId)
        assertNotNull(loaded)
        assertEquals(3, loaded.turnNumber)
        assertTrue(loaded.conversationJson.contains("bye"))
        store.close()
    }

    @Test
    fun `list returns all checkpoints ordered by recency`(@TempDir tempDir: Path) {
        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())

        store.save("session-a", 1, """[{"role":"system","content":"a"}]""")
        store.save("session-b", 1, """[{"role":"system","content":"b"}]""")

        val all = store.list()
        assertEquals(2, all.size)
        assertTrue(all.any { it.sessionId == "session-a" })
        assertTrue(all.any { it.sessionId == "session-b" })
        store.close()
    }

    @Test
    fun `delete older than removes old checkpoints`(@TempDir tempDir: Path) {
        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val sessionId = "delete-test"

        store.save(sessionId, 1, """[{"role":"user","content":"old"}]""")

        // Future timestamp — everything is older, should delete all
        val deleted = store.deleteOlderThan("2099-01-01T00:00:00")
        assertEquals(1, deleted)

        // All checkpoints should be gone
        assertEquals(0, store.count())

        store.close()
    }

    @Test
    fun `delete for session removes all session checkpoints`(@TempDir tempDir: Path) {
        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val sessionId = "delete-session"

        store.save(sessionId, 1, """[{"role":"user","content":"msg1"}]""")
        store.save(sessionId, 2, """[{"role":"user","content":"msg1"},{"role":"assistant","content":"msg2"}]""")
        store.save("other-session", 1, """[{"role":"user","content":"other"}]""")

        assertEquals(3, store.count())

        val deleted = store.deleteForSession(sessionId)
        assertEquals(2, deleted)
        assertEquals(1, store.count())
        assertNotNull(store.load("other-session"))
        assertNull(store.load(sessionId))

        store.close()
    }

    @Test
    fun `empty store has zero count`(@TempDir tempDir: Path) {
        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        assertEquals(0, store.count())
        assertTrue(store.list().isEmpty())
        store.close()
    }

    @Test
    fun `checkpoint manager save and load conversation`(@TempDir tempDir: Path) {
        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val manager = CheckpointManager(store)
        val sessionId = "manager-test"

        val conversation = listOf(
            SystemMessage("You are Spola"),
            UserMessage("Hello!"),
            AssistantMessage(
                content = "Hi there!",
                toolCalls = listOf(ToolCall(id = "call-1", name = "test_tool", arguments = mapOf("arg" to "val"))),
            ),
            ToolResultMessage(toolCallId = "call-1", toolName = "test_tool", content = "Tool result"),
        )

        val id = manager.save(sessionId, 1, conversation)
        assertTrue(id > 0)

        val loaded = manager.loadConversation(sessionId)
        assertNotNull(loaded)
        assertEquals(4, loaded.size)
        assertTrue(loaded[0] is SystemMessage)
        assertTrue(loaded[1] is UserMessage)
        assertTrue(loaded[2] is AssistantMessage)
        assertTrue(loaded[3] is ToolResultMessage)

        val assistantMsg = loaded[2] as AssistantMessage
        assertEquals(1, assistantMsg.toolCalls.size)
        assertEquals("test_tool", assistantMsg.toolCalls[0].name)

        store.close()
    }

    @Test
    fun `checkpoint manager list returns checkpoint data`(@TempDir tempDir: Path) {
        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val manager = CheckpointManager(store)

        manager.save("list-session-1", 1, listOf(SystemMessage("test1")))
        manager.save("list-session-2", 2, listOf(SystemMessage("test2")))

        val list = manager.list()
        assertEquals(2, list.size)
        assertTrue(list.any { it.sessionId == "list-session-1" })
        assertTrue(list.any { it.sessionId == "list-session-2" })

        store.close()
    }

    @Test
    fun `checkpoint manager generate session id produces non-empty string`(@TempDir tempDir: Path) {
        val store = CheckpointStore(tempDir.resolve("checkpoint.db").toString())
        val manager = CheckpointManager(store)

        val id1 = manager.generateSessionId()
        val id2 = manager.generateSessionId()

        assertTrue(id1.value.isNotBlank(), "Session ID should not be blank")
        assertTrue(id1.value.length <= 16, "Session ID should be at most 16 chars")
        // UUID-based: each call produces a unique value
        assertTrue(id1 != id2, "Consecutive calls should produce different IDs")

        store.close()
    }
}
