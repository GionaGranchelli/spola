package dev.spola.compression

data class TokenJuiceRule(
    val name: String,
    val toolPattern: String,
    val strategies: List<CompressionStrategy>,
)

enum class CompressionStrategy {
    SMART_TRUNCATE,
    ERROR_ONLY,
    DEDUP_LINES,
    STRIP_ANSI,
    SUMMARIZE_STATS,
    GROUP_BY_PREFIX,
}
