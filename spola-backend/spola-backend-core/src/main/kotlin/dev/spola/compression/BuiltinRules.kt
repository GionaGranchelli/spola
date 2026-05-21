package dev.spola.compression

val builtinRules: List<TokenJuiceRule> = listOf(
    TokenJuiceRule("git_diff", "git_diff", listOf(CompressionStrategy.SUMMARIZE_STATS, CompressionStrategy.SMART_TRUNCATE)),
    TokenJuiceRule("git_status", "git_status", listOf(CompressionStrategy.SUMMARIZE_STATS)),
    TokenJuiceRule("git_log", "git_log", listOf(CompressionStrategy.SMART_TRUNCATE)),
    TokenJuiceRule("git_commit", "git_commit", listOf(CompressionStrategy.SUMMARIZE_STATS)),
    TokenJuiceRule("shell", "shell", listOf(CompressionStrategy.STRIP_ANSI, CompressionStrategy.DEDUP_LINES, CompressionStrategy.SMART_TRUNCATE)),
    TokenJuiceRule("read_file", "read_file", listOf(CompressionStrategy.SMART_TRUNCATE)),
    TokenJuiceRule("search_files", "search_files", listOf(CompressionStrategy.GROUP_BY_PREFIX, CompressionStrategy.SMART_TRUNCATE)),
    TokenJuiceRule("write_file", "write_file", listOf(CompressionStrategy.SMART_TRUNCATE)),
    TokenJuiceRule("web_fetch", "web_fetch", listOf(CompressionStrategy.STRIP_ANSI, CompressionStrategy.SMART_TRUNCATE)),
    TokenJuiceRule("web_search", "web_search", listOf(CompressionStrategy.SMART_TRUNCATE)),
    TokenJuiceRule("memory_search", "memory_search", listOf(CompressionStrategy.SUMMARIZE_STATS)),
    TokenJuiceRule("task_list", "task_list", listOf(CompressionStrategy.SUMMARIZE_STATS)),
    TokenJuiceRule("edit_file", "edit_file", listOf(CompressionStrategy.SUMMARIZE_STATS)),
    TokenJuiceRule("scheduler_list", "scheduler_list", listOf(CompressionStrategy.SUMMARIZE_STATS)),
)
