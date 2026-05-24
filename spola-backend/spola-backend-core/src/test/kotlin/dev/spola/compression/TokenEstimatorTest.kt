package dev.spola.compression

import dev.spola.AssistantMessage
import dev.spola.SystemMessage
import dev.spola.UserMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenEstimatorTest {

    @Test
    fun `estimate on empty string returns 1`() {
        assertEquals(1, TokenEstimator.estimate(""))
    }

    @Test
    fun `estimate on short text returns expected value`() {
        assertEquals(1, TokenEstimator.estimate("abcd"))
        assertEquals(2, TokenEstimator.estimate("abcdefgh"))
    }

    @Test
    fun `estimate on long text scales linearly`() {
        assertEquals(250, TokenEstimator.estimate("x".repeat(1000)))
        assertEquals(500, TokenEstimator.estimate("x".repeat(2000)))
    }

    @Test
    fun `estimateMessages returns sum with overhead`() {
        val messages = listOf(
            SystemMessage("abcd"),
            UserMessage("abcdefgh"),
            AssistantMessage("abcdefghijkl"),
        )

        val result = TokenEstimator.estimateMessages(messages)

        assertEquals((1 + 4) + (2 + 4) + (3 + 4), result)
    }

    @Test
    fun `exceedsBudget works correctly`() {
        val messages = listOf(
            SystemMessage("abcd"),
            UserMessage("abcdefgh"),
        )

        assertTrue(TokenEstimator.exceedsBudget(messages, budget = 10))
        assertFalse(TokenEstimator.exceedsBudget(messages, budget = 11))
    }
}
