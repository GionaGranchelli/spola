package dev.spola.app.app.navigation

/**
 * Navigation tab definitions for Spola bottom tab bar.
 */
enum class NavigationTab(
    val label: String,
    val emoji: String,
    val description: String,
) {
    Chat("Chat", "\uD83D\uDCAC", "Chat with Spola agent"),
    Tools("Tools", "\uD83D\uDEE0\uFE0F", "Tools & MCP servers"),
    Memory("Memory", "\uD83E\uDDE0", "Memory explorer"),
    Kanban("Kanban", "\uD83D\uDCCB", "Kanban board"),
    Workflows("Workflows", "\uD83D\uDD04", "Workflow definitions"),
    Scheduler("Scheduler", "\u23F0", "Scheduled jobs"),
    Settings("Settings", "\u2699\uFE0F", "Configuration"),
}
