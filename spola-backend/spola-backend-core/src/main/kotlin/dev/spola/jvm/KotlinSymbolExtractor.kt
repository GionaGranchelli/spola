package dev.spola.jvm

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.relativeTo

class KotlinSymbolExtractor {
    // Matches regular declarations: fun, val, var, class, object, interface, enum, annotation
    private val declarationRegex = Regex(
        """\b(public|private|internal|protected)?\s*(?:data\s+|sealed\s+|open\s+|abstract\s+|final\s+|inline\s+|suspend\s+|const\s+|lateinit\s+|tailrec\s+|operator\s+|infix\s+|external\s+|value\s+)*(@interface|annotation\s+class|enum\s+class|class|interface|object|fun|val|var)\s+(?:<[^>]+>\s+)?([A-Za-z_][A-Za-z0-9_]*)""",
        setOf(RegexOption.MULTILINE),
    )

    // Matches extension functions: fun ReceiverType.name(...)
    private val extensionRegex = Regex(
        """\b(public|private|internal|protected)?\s*(?:inline\s+|suspend\s+|operator\s+|infix\s+)*fun\s+(?:<[^>]+>\s+)?([A-Za-z_][A-Za-z0-9_<>.,?\s]*?)\.([A-Za-z_][A-Za-z0-9_]*)""",
        setOf(RegexOption.MULTILINE),
    )

    // Matches top-level val/var with receiver (property extensions)
    private val propertyExtensionRegex = Regex(
        """\b(public|private|internal|protected)?\s*(?:val|var)\s+(?:<[^>]+>\s+)?([A-Za-z_][A-Za-z0-9_<>.,?\s]*?)\.([A-Za-z_][A-Za-z0-9_]*)""",
        setOf(RegexOption.MULTILINE),
    )

    fun extract(file: Path, module: String, root: Path = file.parent ?: Path.of(".")): List<SymbolLocation> {
        if (file.extension != "kt" || !Files.isRegularFile(file)) return emptyList()
        val text = runCatching { Files.readString(file) }.getOrDefault("")
        if (text.isBlank()) return emptyList()
        val stripped = stripComments(text)
        val noStrings = stripStrings(stripped)
        val lineStarts = lineStarts(text)
        val relative = runCatching { file.relativeTo(root).toString() }.getOrElse { file.toString() }

        val regular = declarationRegex.findAll(noStrings).map { match ->
            val token = match.groupValues[2]
            val name = match.groupValues[3]
            val offset = match.range.first + match.value.indexOf(name)
            val line = lineStarts.count { it <= offset }
            val column = offset - (lineStarts.getOrElse(line - 1) { 0 }) + 1
            SymbolLocation(
                name = name,
                kind = token.toKind(),
                file = relative,
                line = line,
                column = column,
                module = module,
                visibility = match.groupValues[1].ifBlank { null },
            )
        }

        val extensions = extensionRegex.findAll(noStrings).map { match ->
            val name = match.groupValues[3]
            val offset = match.range.first + match.value.lastIndexOf(name)
            val line = lineStarts.count { it <= offset }
            val column = offset - (lineStarts.getOrElse(line - 1) { 0 }) + 1
            SymbolLocation(
                name = name,
                kind = SymbolKind.FUN,
                file = relative,
                line = line,
                column = column,
                module = module,
                visibility = match.groupValues[1].ifBlank { null },
            )
        }

        val propertyExtensions = propertyExtensionRegex.findAll(noStrings).map { match ->
            val name = match.groupValues[3]
            val offset = match.range.first + match.value.lastIndexOf(name)
            val line = lineStarts.count { it <= offset }
            val column = offset - (lineStarts.getOrElse(line - 1) { 0 }) + 1
            val kind = if (match.value.contains("val", ignoreCase = false)) SymbolKind.VAL else SymbolKind.VAR
            SymbolLocation(
                name = name,
                kind = kind,
                file = relative,
                line = line,
                column = column,
                module = module,
                visibility = match.groupValues[1].ifBlank { null },
            )
        }

        return (regular + extensions + propertyExtensions).toList()
    }

    private fun String.toKind(): SymbolKind = when {
        this == "class" -> SymbolKind.CLASS
        this == "interface" -> SymbolKind.INTERFACE
        this == "object" -> SymbolKind.OBJECT
        this == "fun" -> SymbolKind.FUN
        this == "val" -> SymbolKind.VAL
        this == "var" -> SymbolKind.VAR
        this.startsWith("enum") -> SymbolKind.ENUM
        else -> SymbolKind.ANNOTATION
    }

    private fun stripComments(text: String): String =
        text.replace(Regex("""(?s)/\*.*?\*/""")) { " ".repeat(it.value.length) }
            .replace(Regex("""//.*""")) { " ".repeat(it.value.length) }

    companion object {
        private val singleLineStringRegex = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        private val charLiteralRegex = Regex("'[^']'|'\\\\[^']'")
        private val tripleQuotedStringRegex = Regex("\"\"\"[\\s\\S]*?\"\"\"")
    }

    /** Replace string and char literals with spaces to prevent false positive matches. */
    private fun stripStrings(text: String): String =
        text.replace(tripleQuotedStringRegex) { " ".repeat(it.value.length) }
            .replace(singleLineStringRegex) { " ".repeat(it.value.length) }
            .replace(charLiteralRegex) { " ".repeat(it.value.length) }

    private fun lineStarts(text: String): List<Int> = buildList {
        add(0)
        text.forEachIndexed { index, char ->
            if (char == '\n') add(index + 1)
        }
    }
}
