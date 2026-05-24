package dev.spola.compression

import dev.spola.AssistantMessage
import dev.spola.ChatMessage
import dev.spola.SystemMessage
import dev.spola.ToolCall
import dev.spola.ToolResultMessage
import dev.spola.UserMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConversationCompactorTest {

    @Test
    fun `no-op on short transcripts`() {
        val messages = listOf(
            SystemMessage("system"),
            UserMessage("user"),
            AssistantMessage("assistant"),
        )
        val compactor = ConversationCompactor(
            ConversationCompactionConfig(enabled = true, threshold = 5),
        )

        val result = compactor.compact(messages)

        assertSame(messages, result)
    }

    @Test
    fun `preserve system message at index zero after compaction`() {
        val system = SystemMessage("system rules")
        val messages = listOf(
            system,
            UserMessage("old user"),
            AssistantMessage("old assistant"),
            UserMessage("recent user"),
            AssistantMessage("recent assistant"),
        )
        val compactor = ConversationCompactor(
            ConversationCompactionConfig(
                enabled = true,
                threshold = 2,
                retainRecentTurns = 1,
            ),
        )

        val result = compactor.compact(messages)

        assertEquals(system, result.first())
        assertTrue(result.size < messages.size)
    }

    @Test
    fun `preserve latest user message`() {
        val latestUser = UserMessage("latest user question")
        val messages = listOf(
            SystemMessage("system"),
            UserMessage("older user"),
            AssistantMessage("older assistant"),
            latestUser,
            AssistantMessage("latest assistant"),
        )
        val compactor = ConversationCompactor(
            ConversationCompactionConfig(
                enabled = true,
                threshold = 2,
                retainRecentTurns = 1,
            ),
        )

        val result = compactor.compact(messages)

        assertTrue(result.contains(latestUser))
    }

    @Test
    fun `preserve last k recent turns`() {
        val u1 = UserMessage("u1")
        val a1 = AssistantMessage("a1")
        val u2 = UserMessage("u2")
        val a2 = AssistantMessage("a2")
        val u3 = UserMessage("u3")
        val a3 = AssistantMessage("a3")
        val u4 = UserMessage("u4")
        val a4 = AssistantMessage("a4")
        val messages = listOf(SystemMessage("system"), u1, a1, u2, a2, u3, a3, u4, a4)
        val compactor = ConversationCompactor(
            ConversationCompactionConfig(
                enabled = true,
                threshold = 3,
                retainRecentTurns = 2,
            ),
        )

        val result = compactor.compact(messages)

        assertTrue(result.containsAll(listOf(u3, a3, u4, a4)))
        assertFalse(result.contains(u1))
        assertFalse(result.contains(a1))
    }

    @Test
    fun `preserve pinned messages`() {
        val pinnedAssistant = assistantWithToolCall("call-1", "search", "pinned assistant")
        val pinnedToolResult = ToolResultMessage("call-1", "search", "pinned tool result")
        val messages = listOf(
            SystemMessage("system"),
            UserMessage("old user"),
            AssistantMessage("old assistant"),
            pinnedAssistant,
            pinnedToolResult,
            UserMessage("recent user"),
            AssistantMessage("recent assistant"),
        )
        val compactor = ConversationCompactor(
            ConversationCompactionConfig(
                enabled = true,
                threshold = 2,
                retainRecentTurns = 1,
            ),
        )

        val result = compactor.compact(messages, pinnedMessageIds = setOf(3))

        assertTrue(result.contains(pinnedAssistant))
        assertTrue(result.contains(pinnedToolResult))
    }

    @Test
    fun `compacts older tool-heavy history into one summary block`() {
        val messages = listOf(
            SystemMessage("system"),
            UserMessage("old request"),
            assistantWithToolCall("call-1", "search", "calling search"),
            ToolResultMessage("call-1", "search", "search output"),
            assistantWithToolCall("call-2", "fetch", "calling fetch"),
            ToolResultMessage("call-2", "fetch", "fetch error: failed request"),
            UserMessage("recent user"),
            AssistantMessage("recent assistant"),
        )
        val compactor = ConversationCompactor(
            ConversationCompactionConfig(
                enabled = true,
                threshold = 2,
                retainRecentTurns = 1,
            ),
        )

        val result = compactor.compact(messages)
        val summaries = result.filterIsInstance<AssistantMessage>()
            .filter { it.content.contains("[Conversation summary up to turn") }

        assertEquals(1, summaries.size)
        assertTrue(summaries.single().content.contains("Tools used: search, fetch"))
        assertTrue(summaries.single().content.contains("Tool calls: 2, Tool results: 2"))
        assertTrue(summaries.single().content.contains("Errors encountered: 1"))
    }

    @Test
    fun `never leaves orphaned tool results without their assistant`() {
        val messages = listOf(
            SystemMessage("system"),
            UserMessage("u1"),
            assistantWithToolCall("call-1", "search", "a1"),
            ToolResultMessage("call-1", "search", "r1"),
            UserMessage("u2"),
            assistantWithToolCall("call-2", "fetch", "a2"),
            ToolResultMessage("call-2", "fetch", "r2"),
            UserMessage("u3"),
            AssistantMessage("a3"),
        )
        val compactor = ConversationCompactor(
            ConversationCompactionConfig(
                enabled = true,
                threshold = 2,
                retainRecentTurns = 1,
            ),
        )

        val result = compactor.compact(messages)

        assertTrue(hasNoOrphanedToolResults(result))
    }

    @Test
    fun `repeated compaction is idempotent`() {
        val messages = listOf(
            SystemMessage("system"),
            UserMessage("u1"),
            assistantWithToolCall("call-1", "search", "a1"),
            ToolResultMessage("call-1", "search", "r1"),
            UserMessage("u2"),
            AssistantMessage("a2"),
        )
        val compactor = ConversationCompactor(
            ConversationCompactionConfig(
                enabled = true,
                threshold = 2,
                retainRecentTurns = 1,
            ),
        )

        val once = compactor.compact(messages)
        val twice = compactor.compact(once)

        assertEquals(once, twice)
    }

    @Test
    fun `disabled compactor returns messages unchanged`() {
        val messages = listOf(
            SystemMessage("system"),
            UserMessage("user"),
            AssistantMessage("assistant"),
        )
        val compactor = ConversationCompactor(
            ConversationCompactionConfig(enabled = false, threshold = 1),
        )

        val result = compactor.compact(messages)

        assertSame(messages, result)
    }

    @Test
    fun `token budget trigger fires when exceeded`() {
        val messages = listOf(
            SystemMessage("s"),
            UserMessage("u1"),
            AssistantMessage("a1"),
            UserMessage("u2"),
            AssistantMessage("a2"),
            UserMessage("u3"),
            AssistantMessage("a3"),
        )
        val compactor = ConversationCompactor(
            ConversationCompactionConfig(
                enabled = true,
                trigger = ConversationCompactionConfig.Trigger.TOKEN_BUDGET,
                retainRecentTurns = 1,
            ),
        )

        val result = compactor.compact(messages, emptySet(), tokenBudget = 10)

        assertFalse(result === messages)
        assertTrue(
            result.filterIsInstance<AssistantMessage>()
                .any { it.content.contains("[Conversation summary up to turn") },
        )
    }

    @Test
    fun `token budget trigger does not fire when within budget`() {
        val messages = listOf(
            SystemMessage("system"),
            UserMessage("user"),
            AssistantMessage("assistant"),
        )
        val compactor = ConversationCompactor(
            ConversationCompactionConfig(
                enabled = true,
                trigger = ConversationCompactionConfig.Trigger.TOKEN_BUDGET,
                retainRecentTurns = 1,
            ),
        )

        val result = compactor.compact(messages, emptySet(), tokenBudget = 100)

        assertSame(messages, result)
    }

    private fun assistantWithToolCall(
        id: String,
        name: String,
        content: String,
    ) = AssistantMessage(
        content = content,
        toolCalls = listOf(
            ToolCall(
                id = id,
                name = name,
                arguments = mapOf("query" to "value"),
            ),
        ),
    )

    private fun hasNoOrphanedToolResults(messages: List<ChatMessage>): Boolean {
        val seenToolCallIds = mutableSetOf<String>()
        for (message in messages) {
            when (message) {
                is AssistantMessage -> seenToolCallIds += message.toolCalls.map { it.id }
                is ToolResultMessage -> if (message.toolCallId !in seenToolCallIds) return false
                else -> {}
            }
        }
        return true
    }
}
