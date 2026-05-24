package dev.spola.compression

import dev.spola.*

/**
 * Compacts conversation history by keeping critical messages intact
 * and summarizing older segments to stay within context window limits.
 *
 * Pinned messages (by ID) are always preserved unchanged.
 */
class ConversationCompactor(
    private val config: ConversationCompactionConfig = ConversationCompactionConfig(),
) {
    /**
     * Compact a conversation transcript.
     *
     * @param messages Full conversation transcript
     * @param pinnedMessageIds Set of message IDs that must be kept intact
     * @return Compacted transcript
     */
    fun compact(
        messages: List<ChatMessage>,
        pinnedMessageIds: Set<Int> = emptySet(),
    ): List<ChatMessage> {
        if (!shouldCompact(messages)) return messages

        return compactInternal(messages, pinnedMessageIds)
    }

    /**
     * Compact a conversation transcript using a caller-supplied token budget.
     *
     * This is only used when the configured trigger is [ConversationCompactionConfig.Trigger.TOKEN_BUDGET].
     */
    fun compact(
        messages: List<ChatMessage>,
        pinnedMessageIds: Set<Int>,
        tokenBudget: Int,
    ): List<ChatMessage> {
        if (!shouldCompact(messages, tokenBudget)) return messages

        return compactInternal(messages, pinnedMessageIds)
    }

    private fun shouldCompact(messages: List<ChatMessage>, tokenBudget: Int? = null): Boolean {
        if (!config.enabled) return false
        if (messages.isEmpty()) return false

        return when (config.trigger) {
            ConversationCompactionConfig.Trigger.MESSAGE_COUNT -> messages.size > config.threshold
            ConversationCompactionConfig.Trigger.TOKEN_BUDGET ->
                TokenEstimator.exceedsBudget(messages, tokenBudget ?: config.tokenBudgetThreshold)
            ConversationCompactionConfig.Trigger.OFF -> false
        }
    }

    private fun compactInternal(
        messages: List<ChatMessage>,
        pinnedMessageIds: Set<Int>,
    ): List<ChatMessage> {
        val protectedIndices = findProtectedIndices(messages, pinnedMessageIds)
        if (protectedIndices.size >= messages.size) return messages

        val result = mutableListOf<ChatMessage>()
        val compactable = mutableListOf<Pair<Int, ChatMessage>>()

        for (i in messages.indices) {
            if (i in protectedIndices) {
                if (compactable.isNotEmpty()) {
                    result.add(buildSummary(compactable))
                    compactable.clear()
                }
                result.add(messages[i])
            } else {
                compactable.add(i to messages[i])
            }
        }

        if (compactable.isNotEmpty()) {
            result.add(buildSummary(compactable))
        }

        return result
    }

    private fun findProtectedIndices(
        messages: List<ChatMessage>,
        pinnedMessageIds: Set<Int>,
    ): Set<Int> {
        val protected = mutableSetOf<Int>()
        // System messages are always protected
        for (i in messages.indices) {
            if (messages[i] is SystemMessage) {
                protected.add(i)
            }
        }

        // Summary messages are always protected (maintains idempotency)
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is AssistantMessage && msg.content.startsWith("[Conversation summary up to turn")) {
                protected.add(i)
            }
        }

        for (i in messages.indices) {
            if (i in pinnedMessageIds) {
                protected.add(i)
                val msg = messages[i]
                if (msg is AssistantMessage && msg.toolCalls.isNotEmpty()) {
                    for (j in (i + 1) until messages.size) {
                        if (messages[j] is ToolResultMessage) {
                            protected.add(j)
                        } else {
                            break
                        }
                    }
                }
            }
        }

        protected.addAll(findRecentTurns(messages))
        return protected
    }

    /**
     * Find the most recent complete assistant+tool-result turn windows.
     */
    private fun findRecentTurns(messages: List<ChatMessage>): Set<Int> {
        val result = mutableSetOf<Int>()
        var turnsFound = 0

        for (i in messages.indices.reversed()) {
            if (turnsFound >= config.retainRecentTurns) break
            val msg = messages[i]
            // Skip summary messages to maintain idempotency
            if (msg is AssistantMessage && msg.content.startsWith("[Conversation summary up to turn")) {
                continue
            }
            when (msg) {
                is ToolResultMessage -> {
                    result.add(i)
                }
                is AssistantMessage -> {
                    result.add(i)
                    turnsFound++
                    // Also protect the preceding user message that prompted this response
                    for (j in (i - 1) downTo 0) {
                        if (messages[j] is UserMessage && j !in result) {
                            result.add(j)
                            break
                        }
                    }
                }
                is UserMessage -> {
                    // Only keep if we haven't hit the limit yet
                    if (turnsFound < config.retainRecentTurns) {
                        result.add(i)
                    }
                }
                else -> {}
            }
        }

        return result
    }

    private fun buildSummary(segment: List<Pair<Int, ChatMessage>>): ChatMessage {
        val endIndex = segment.last().first
        val messages = segment.map { it.second }

        val toolCalls = messages.count {
            it is AssistantMessage && it.toolCalls.isNotEmpty()
        }
        val toolsUsed = messages.filterIsInstance<AssistantMessage>()
            .flatMap { it.toolCalls }
            .map { it.name }
            .distinct()
        val errors = messages.filterIsInstance<ToolResultMessage>()
            .filter {
                it.content.contains("error", ignoreCase = true) ||
                    it.content.contains("fail", ignoreCase = true)
            }
        val totalToolResults = messages.count { it is ToolResultMessage }

        val summary = buildString {
            appendLine("[Conversation summary up to turn ${endIndex + 1}]")
            appendLine("Messages compacted: ${messages.size}")
            if (toolsUsed.isNotEmpty()) {
                appendLine("Tools used: ${toolsUsed.joinToString(", ")}")
            }
            appendLine("Tool calls: $toolCalls, Tool results: $totalToolResults")
            if (errors.isNotEmpty()) {
                appendLine("Errors encountered: ${errors.size}")
                errors.take(3).forEach { err ->
                    val snippet = err.content.take(200).replace("\n", " ")
                    appendLine("  - $snippet")
                }
            }
        }

        return AssistantMessage(content = summary.trimEnd())
    }
}

data class ConversationCompactionConfig(
    val enabled: Boolean = false,
    val trigger: Trigger = Trigger.MESSAGE_COUNT,
    val threshold: Int = 40,
    val tokenBudgetThreshold: Int = 80000,
    val retainRecentTurns: Int = 5,
    val style: Style = Style.STRUCTURED_SUMMARY,
) {
    enum class Trigger { MESSAGE_COUNT, TOKEN_BUDGET, OFF }
    enum class Style { STRUCTURED_SUMMARY }
}
