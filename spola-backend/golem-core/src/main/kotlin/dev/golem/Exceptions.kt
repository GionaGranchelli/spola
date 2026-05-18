package dev.spola

/**
 * Exception thrown when the agent exceeds the maximum number of turns.
 */
class MaxTurnsExceededException(val maxTurns: Int) :
    RuntimeException("Agent exceeded maximum of $maxTurns turns")
