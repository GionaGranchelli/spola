package dev.spola.compression

import dev.spola.ChatMessage

/**
 * Estimates token count for conversations without calling an external API.
 * Uses conservative char-to-token ratio (4 chars ≈ 1 token).
 */
object TokenEstimator {
    /** Default ratio: 1 token ≈ 4 characters */
    private const val CHARS_PER_TOKEN = 4.0

    /**
     * Estimate tokens for a text string.
     */
    fun estimate(text: String): Int = (text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)

    /**
     * Estimate total tokens for a list of messages.
     * Adds ~4 tokens of overhead per message for role markers and formatting.
     */
    fun estimateMessages(messages: List<ChatMessage>): Int {
        var total = 0
        for (msg in messages) {
            total += estimate(msg.content)
            total += 4
        }
        return total
    }

    /**
     * Check if a conversation exceeds a token budget.
     */
    fun exceedsBudget(messages: List<ChatMessage>, budget: Int): Boolean =
        estimateMessages(messages) > budget
}
