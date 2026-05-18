package dev.spola.skill

internal fun extractSections(body: String): List<Section> {
    if (body.isBlank()) {
        return emptyList()
    }

    val headingRegex = Regex("""^(#{1,6})\s+(.+?)\s*$""")
    val sections = mutableListOf<Section>()
    val buffer = mutableListOf<String>()
    var currentTitle = "Overview"
    var seenHeading = false

    body.lineSequence().forEach { line ->
        val match = headingRegex.matchEntire(line)
        if (match != null) {
            if (buffer.isNotEmpty() || seenHeading || sections.isEmpty()) {
                sections += Section(currentTitle, buffer.joinToString("\n").trim())
                buffer.clear()
            }
            currentTitle = match.groupValues[2].trim()
            seenHeading = true
        } else {
            buffer += line
        }
    }

    if (buffer.isNotEmpty() || seenHeading) {
        sections += Section(currentTitle, buffer.joinToString("\n").trim())
    }

    return sections.filter { it.title.isNotBlank() || it.body.isNotBlank() }
}
