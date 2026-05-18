package dev.spola.jvm

data class FreshnessPolicy(
    val maxAgeMs: Long,
    val preferReindex: Boolean = false,
) {
    fun isStale(lastScannedAt: Long?): Boolean {
        if (lastScannedAt == null) return true
        return System.currentTimeMillis() - lastScannedAt > maxAgeMs
    }
}

class IndexFreshnessPolicy(
    private val defaults: Map<String, FreshnessPolicy> = mapOf(
        QUERY_SYMBOL_SEARCH to FreshnessPolicy(maxAgeMs = 5 * 60 * 1000L),
        QUERY_DEPENDENCY_TRACE to FreshnessPolicy(maxAgeMs = 15 * 60 * 1000L),
        QUERY_PROJECT_OVERVIEW to FreshnessPolicy(maxAgeMs = 60 * 60 * 1000L),
    ),
    private val fallback: FreshnessPolicy = FreshnessPolicy(maxAgeMs = 15 * 60 * 1000L),
) {
    fun forQueryType(queryType: String): FreshnessPolicy = defaults[queryType] ?: fallback

    companion object {
        const val QUERY_SYMBOL_SEARCH = "symbol_search"
        const val QUERY_DEPENDENCY_TRACE = "dependency_trace"
        const val QUERY_PROJECT_OVERVIEW = "overview"
        const val QUERY_CONTEXT_PACK = "context_pack"
        const val QUERY_FILE_OUTLINE = "file_outline"
        const val QUERY_CHANGE_IMPACT = "change_impact"
        const val QUERY_FAILURE_EXPLAIN = "failure_explain"
        const val QUERY_VERIFY_PLAN = "verify_plan"
    }
}
