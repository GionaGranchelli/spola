package dev.spola.compression

import org.slf4j.LoggerFactory

/**
 * TokenJuice — terminal-output compaction engine for Spola.
 *
 * Compresses verbose tool output before it enters the LLM context window
 * using a configurable set of compression strategies.
 *
 * Inspired by OpenHuman's TokenJuice and RTK (Rust Token Killer).
 */
object TokenJuice {

    private val logger = LoggerFactory.getLogger(TokenJuice::class.java)

    internal fun toolPatternToRegex(toolPattern: String): Regex {
        val escapedPattern = toolPattern
            // Keep trailing empty segments so patterns like "shell*" work as expected.
            .split("*", ignoreCase = false, limit = Int.MAX_VALUE)
            .joinToString(".*") { Regex.escape(it) }
        return Regex(escapedPattern)
    }

    private val rules: List<TokenJuiceRule> = builtinRules
    private const val MAX_CHARS = 4000
    private const val HEAD_TAIL_RATIO = 0.7
    private const val TRUNCATE_NOTICE_RESERVE = 60

    /**
     * Compact a tool's output using matching rules and strategies.
     *
     * @param toolName The name of the tool that produced the output
     * @param output The raw output text to compact
     * @param enabled Whether compression is enabled (set false to bypass)
     * @return Compressed output, or original if smaller than threshold or disabled
     */
    fun compact(toolName: String, output: String, enabled: Boolean = true): String {
        if (!enabled) return output

        val matchingRules = rules.filter { rule ->
            toolName.matches(toolPatternToRegex(rule.toolPattern))
        }
        val strategies = matchingRules.flatMap { it.strategies }.distinct()

        var result = output
        // Apply non-truncation strategies regardless of size
        for (strategy in strategies) {
            result = when (strategy) {
                CompressionStrategy.STRIP_ANSI -> stripAnsi(result)
                CompressionStrategy.DEDUP_LINES -> dedupLines(result)
                CompressionStrategy.SUMMARIZE_STATS -> summarizeStats(result)
                CompressionStrategy.GROUP_BY_PREFIX -> groupByPrefix(result)
                CompressionStrategy.ERROR_ONLY -> errorOnly(result)
                // Skip SMART_TRUNCATE for small outputs; always apply for large
                CompressionStrategy.SMART_TRUNCATE -> {
                    if (result.length > MAX_CHARS) smartTruncate(result, MAX_CHARS, HEAD_TAIL_RATIO)
                    else result
                }
            }
        }
        val saved = output.length - result.length
        logger.debug(
            "TokenJuice tool=$toolName original=${output.length} compressed=${result.length} " +
                "saved=$saved strategies=${strategies.joinToString(",")}"
        )
        return result
    }

    /** Remove ANSI escape sequences from text. */
    private fun stripAnsi(text: String): String =
        text.replace(Regex("\u001B\\[[;\\d]*[ -/]*[@-~]"), "")

    /** Remove consecutive duplicate lines. */
    private fun dedupLines(text: String): String {
        val lines = text.lines()
        val result = mutableListOf<String>()
        var prev = ""
        for (line in lines) {
            if (line != prev) {
                result.add(line)
                prev = line
            }
        }
        return result.joinToString("\n")
    }

    /** Extract +N/-N stats from git-like output. Falls back to smart truncation. */
    private fun summarizeStats(text: String): String {
        val changedMatch = Regex("(\\d+) files? changed").find(text)
        val insertions = Regex("(\\d+) insertions?").find(text)
        val deletions = Regex("(\\d+) deletions?").find(text)

        if (changedMatch != null || insertions != null || deletions != null) {
            val parts = mutableListOf<String>()
            changedMatch?.let { parts.add(it.value) }
            if (insertions != null || deletions != null) {
                parts.add(
                    (insertions?.value ?: "0 insertions(+)") +
                    ", " +
                    (deletions?.value ?: "0 deletions(-)")
                )
            }
            return parts.joinToString("; ")
        }
        // Fallback to truncation
        return smartTruncate(text, MAX_CHARS / 2, HEAD_TAIL_RATIO)
    }

    /** Group lines by common prefix, show group size for large groups. */
    private fun groupByPrefix(text: String): String {
        val lines = text.lines().filter { it.isNotBlank() }
        val groups = mutableMapOf<String, MutableList<String>>()
        for (line in lines) {
            val prefix = line.take(30).substringBefore(":").substringBefore(" ").trim()
            groups.getOrPut(prefix) { mutableListOf() }.add(line)
        }
        return groups.entries.joinToString("\n") { (prefix, entries) ->
            if (entries.size <= 3) entries.joinToString("\n")
            else "$prefix: ${entries.size} matches"
        }
    }

    /** Keep first 70% of usable chars, last 30%, with a truncation notice. */
    private fun smartTruncate(text: String, maxChars: Int, headRatio: Double): String {
        if (text.length <= maxChars) return text
        val usableChars = maxChars - TRUNCATE_NOTICE_RESERVE
        val headLen = (usableChars * headRatio).toInt()
        val tailLen = usableChars - headLen
        val head = text.take(headLen)
        val tail = text.takeLast(tailLen)
        val truncated = text.length - usableChars
        return "$head\n... [$truncated more chars truncated]\n$tail"
    }

    /** Keep only lines containing error/fail keywords. */
    private fun errorOnly(text: String): String {
        val lines = text.lines().filter { it.isNotBlank() }
        val errors = lines.filter {
            it.contains("error", ignoreCase = true) ||
            it.contains("fail", ignoreCase = true) ||
            it.contains("FAILED", ignoreCase = false)
        }
        return if (errors.isNotEmpty()) {
            errors.joinToString("\n")
        } else {
            smartTruncate(text, MAX_CHARS, HEAD_TAIL_RATIO)
        }
    }
}
