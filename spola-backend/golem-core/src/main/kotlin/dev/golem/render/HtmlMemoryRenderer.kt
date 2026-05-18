package dev.spola.render

/**
 * Converts markdown-formatted memory values into HTML for human-readable views.
 *
 * The primary consumer of memory is the AI (markdown is token-efficient for that).
 * This renderer is used ONLY for the human-facing UI — the API endpoint
 * GET /api/memory/{key}/html and the `golem memory render <key>` CLI command.
 *
 * Supports:
 * - Bold (**text** → &lt;strong&gt;)
 * - Italic (*text* → &lt;em&gt;)
 * - Inline code (`code` → &lt;code&gt;)
 * - Code blocks (``` → &lt;pre&gt;&lt;code&gt;)
 * - Unordered lists (- / * → &lt;ul&gt;&lt;li&gt;)
 * - Ordered lists (1. → &lt;ol&gt;&lt;li&gt;)
 * - Headers (# → &lt;hX&gt;)
 * - Links ([text](url) → &lt;a href&gt;)
 * - Line breaks (\\n → &lt;br&gt;)
 * - Tables (| col | col | → &lt;table&gt;)
 * - Horizontal rules (--- → &lt;hr&gt;)
 *
 * This is intentionally a focused lightweight converter, NOT a full markdown
 * parser. For complex documents, use a proper library (commonmark, flexmark).
 */
object HtmlMemoryRenderer {

    /**
     * Convert a markdown string to HTML.
     * Wraps the result in a minimal document if [fullDocument] is true.
     */
    fun render(markdown: String, fullDocument: Boolean = false): String {
        val body = convertToHtml(markdown)
        return if (fullDocument) wrapDocument(body) else body
    }

    /**
     * Generate a complete standalone HTML page for viewing all memories.
     */
    fun renderMemoryList(
        entries: List<MemoryEntry>,
        query: String? = null,
        baseCss: String = DEFAULT_CSS,
    ): String = buildString {
        appendLine("<!DOCTYPE html>")
        appendLine("<html lang=\"en\">")
        appendLine("<head>")
        appendLine("<meta charset=\"UTF-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        appendLine("<title>Golem Memory" + (query?.let { " — Search: " + sanitizeHtml(it) } ?: "") + "</title>")
        appendLine("<style>$baseCss</style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("<div class=\"container\">")
        appendLine("<h1>🧠 Golem Memory</h1>")
        if (query != null) {
            appendLine("<p class=\"query-badge\">Search: <code>${sanitizeHtml(query)}</code></p>")
        }
        appendLine("<p class=\"count\">${entries.size} entr${if (entries.size == 1) "y" else "ies"}</p>")

        for (entry in entries) {
            appendLine("<div class=\"memory-entry\">")
            appendLine("<div class=\"memory-header\">")
            appendLine("<span class=\"memory-key\">${sanitizeHtml(entry.key)}</span>")
            appendLine("<span class=\"memory-date\">${sanitizeHtml(entry.updatedAt)}</span>")
            appendLine("</div>")
            appendLine("<div class=\"memory-body\">")
            appendLine(convertToHtml(entry.value))
            appendLine("</div>")
            appendLine("</div>")
        }

        appendLine("</div>")
        appendLine("</body>")
        appendLine("</html>")
    }

    data class MemoryEntry(
        val key: String,
        val value: String,
        val updatedAt: String,
    )

    // ── Internal ─────────────────────────────────────────

    private fun convertToHtml(md: String): String {
        val lines = md.lines()
        val html = StringBuilder()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            when {
                // Code block
                line.trimStart().startsWith("```") -> {
                    val lang = line.trimStart().removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        code.appendLine(lines[i])
                        i++
                    }
                    html.appendLine("<pre><code${if (lang.isNotBlank()) " class=\"language-${sanitizeHtml(lang)}\"" else ""}>${sanitizeHtml(code.toString().trimEnd())}</code></pre>")
                }

                // Header
                line.startsWith("###### ") -> html.appendLine("<h6>${inlineHtml(line.removePrefix("###### "))}</h6>")
                line.startsWith("##### ") -> html.appendLine("<h5>${inlineHtml(line.removePrefix("##### "))}</h5>")
                line.startsWith("#### ") -> html.appendLine("<h4>${inlineHtml(line.removePrefix("#### "))}</h4>")
                line.startsWith("### ") -> html.appendLine("<h3>${inlineHtml(line.removePrefix("### "))}</h3>")
                line.startsWith("## ") -> html.appendLine("<h2>${inlineHtml(line.removePrefix("## "))}</h2>")
                line.startsWith("# ") -> html.appendLine("<h1>${inlineHtml(line.removePrefix("# "))}</h1>")

                // Horizontal rule
                line.trim().matches(Regex("^-{3,}$")) -> html.appendLine("<hr>")

                // Table row
                line.trimStart().startsWith("|") && line.trimEnd().endsWith("|") -> {
                    if (line.trim().matches(Regex("\\|[-:| ]+\\|"))) {
                        // Separator row — skip
                    } else {
                        // Check if previous line was also a table row
                        val isHeader = i + 1 < lines.size && lines[i + 1].trim().matches(Regex("\\|[-:| ]+\\|"))
                        val cells = line.split("|").drop(1).dropLast(1).map { it.trim() }
                        if (isHeader) {
                            html.appendLine("<table><thead><tr>")
                            cells.forEach { html.appendLine("<th>${inlineHtml(it)}</th>") }
                            html.appendLine("</tr></thead><tbody>")
                        } else {
                            html.appendLine("<tr>")
                            cells.forEach { html.appendLine("<td>${inlineHtml(it)}</td>") }
                            html.appendLine("</tr>")
                        }
                    }
                }

                // Unordered list
                (line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ")) -> {
                    html.appendLine("<ul>")
                    html.appendLine("<li>${inlineHtml(line.trimStart().removePrefix("- ").removePrefix("* "))}</li>")
                    i++
                    while (i < lines.size) {
                        val next = lines[i].trimStart()
                        if (next.startsWith("- ") || next.startsWith("* ")) {
                            html.appendLine("<li>${inlineHtml(next.removePrefix("- ").removePrefix("* "))}</li>")
                            i++
                        } else if (next.isBlank()) {
                            break
                        } else {
                            break
                        }
                    }
                    html.appendLine("</ul>")
                    continue
                }

                // Ordered list
                line.trimStart().matches(Regex("^\\d+\\.\\s.*")) -> {
                    html.appendLine("<ol>")
                    html.appendLine("<li>${inlineHtml(line.trimStart().replaceFirst(Regex("^\\d+\\.\\s"), ""))}</li>")
                    i++
                    while (i < lines.size) {
                        val next = lines[i].trimStart()
                        if (next.matches(Regex("^\\d+\\.\\s.*"))) {
                            html.appendLine("<li>${inlineHtml(next.replaceFirst(Regex("^\\d+\\.\\s"), ""))}</li>")
                            i++
                        } else if (next.isBlank()) {
                            break
                        } else {
                            break
                        }
                    }
                    html.appendLine("</ol>")
                    continue
                }

                // Blank line
                line.isBlank() -> {
                    if (html.isNotEmpty() && !html.toString().endsWith("</table>\n")) {
                        // close open table if needed
                    }
                }

                // Paragraph
                else -> {
                    html.appendLine("<p>${inlineHtml(line)}</p>")
                }
            }
            i++
        }

        // Close any open tbody + table
        val result = html.toString()
        return if (result.contains("<thead>") && !result.contains("</tbody></table>")) {
            result + "</tbody></table>\n"
        } else {
            result
        }
    }

    private fun inlineHtml(text: String): String {
        var result = sanitizeHtml(text)
        // Inline code
        result = result.replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
        // Bold
        result = result.replace(Regex("\\*\\*(.+?)\\*\\*")) { "<strong>${it.groupValues[1]}</strong>" }
        // Italic
        result = result.replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)")) { "<em>${it.groupValues[1]}</em>" }
        // Links
        result = result.replace(Regex("\\[([^]]+)]\\(([^)]+)\\)")) {
            val url = sanitizeLink(it.groupValues[2])
            "<a href=\"${escapeAttr(url)}\">${it.groupValues[1]}</a>"
        }
        return result
    }

    private fun sanitizeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun sanitizeLink(url: String): String {
        val trimmed = url.trim()
        val normalized = trimmed.lowercase()
        return if (
            normalized.startsWith("javascript:") ||
            normalized.startsWith("data:") ||
            normalized.startsWith("vbscript:")
        ) {
            "#"
        } else {
            trimmed
        }
    }

    private fun escapeAttr(text: String): String = text
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun wrapDocument(body: String): String = buildString {
        appendLine("<!DOCTYPE html>")
        appendLine("<html lang=\"en\">")
        appendLine("<head>")
        appendLine("<meta charset=\"UTF-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        appendLine("<title>Golem Memory</title>")
        appendLine("<style>$DEFAULT_CSS</style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("<div class=\"container\">")
        append(body)
        appendLine("</div>")
        appendLine("</body>")
        appendLine("</html>")
    }

    private val DEFAULT_CSS = """
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 800px; margin: 0 auto; padding: 20px; background: #0d1117; color: #e6edf3; }
        .container { max-width: 800px; margin: 0 auto; }
        h1 { border-bottom: 1px solid #30363d; padding-bottom: 8px; }
        .query-badge { background: #1f6feb; color: white; padding: 4px 12px; border-radius: 12px; display: inline-block; font-size: 14px; }
        .count { color: #8b949e; font-size: 14px; }
        .memory-entry { background: #161b22; border: 1px solid #30363d; border-radius: 8px; margin: 16px 0; padding: 16px; }
        .memory-header { display: flex; justify-content: space-between; margin-bottom: 12px; font-size: 13px; }
        .memory-key { font-weight: 600; color: #58a6ff; }
        .memory-date { color: #8b949e; }
        .memory-body { line-height: 1.6; }
        .memory-body p { margin: 8px 0; }
        .memory-body code { background: #1f2937; padding: 2px 6px; border-radius: 4px; font-size: 13px; }
        .memory-body pre { background: #1f2937; border: 1px solid #30363d; border-radius: 6px; padding: 12px; overflow-x: auto; }
        .memory-body pre code { background: transparent; padding: 0; }
        .memory-body table { border-collapse: collapse; width: 100%; margin: 8px 0; }
        .memory-body th, .memory-body td { border: 1px solid #30363d; padding: 6px 10px; text-align: left; }
        .memory-body th { background: #1f2937; font-weight: 600; }
        .memory-body a { color: #58a6ff; text-decoration: none; }
        .memory-body a:hover { text-decoration: underline; }
        .memory-body hr { border: none; border-top: 1px solid #30363d; margin: 16px 0; }
        .memory-body ul, .memory-body ol { padding-left: 24px; }
        .memory-body li { margin: 4px 0; }
    """.trimIndent()
}
