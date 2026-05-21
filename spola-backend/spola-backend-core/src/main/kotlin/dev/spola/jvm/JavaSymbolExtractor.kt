package dev.spola.jvm

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.relativeTo

class JavaSymbolExtractor {
    private val typeRegex = Regex("""\b(public|private|protected)?\s*(?:abstract\s+|final\s+|static\s+)*(@interface|class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val methodRegex = Regex("""\b(public|private|protected)?\s*(?:static\s+|final\s+|abstract\s+|synchronized\s+|native\s+)*[A-Za-z_][A-Za-z0-9_<>\[\], ?]*\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;{}]*\)\s*(?:throws\s+[^{]+)?\{""")
    private val fieldRegex = Regex("""\b(public|private|protected)?\s*(?:static\s+|final\s+|volatile\s+|transient\s+)*[A-Za-z_][A-Za-z0-9_<>\[\], ?]*\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:=|;)""")

    fun extract(file: Path, module: String, root: Path = file.parent ?: Path.of(".")): List<SymbolLocation> {
        if (file.extension != "java" || !Files.isRegularFile(file)) return emptyList()
        val text = runCatching { Files.readString(file) }.getOrDefault("")
        if (text.isBlank()) return emptyList()
        val stripped = stripComments(text)
        val lineStarts = lineStarts(text)
        val relative = runCatching { file.relativeTo(root).toString() }.getOrElse { file.toString() }

        val symbols = mutableListOf<SymbolLocation>()
        typeRegex.findAll(stripped).forEach { match ->
            symbols += location(match, match.groupValues[3], match.groupValues[1], relative, module, lineStarts, match.groupValues[2].toKind())
        }
        methodRegex.findAll(stripped).forEach { match ->
            val name = match.groupValues[2]
            if (name !in javaKeywords) {
                symbols += location(match, name, match.groupValues[1], relative, module, lineStarts, SymbolKind.FUN)
            }
        }
        fieldRegex.findAll(stripped).forEach { match ->
            val name = match.groupValues[2]
            if (name !in javaKeywords) {
                symbols += location(match, name, match.groupValues[1], relative, module, lineStarts, SymbolKind.VAL)
            }
        }
        return symbols.distinctBy { "${it.kind}:${it.file}:${it.line}:${it.name}" }
    }

    private fun location(
        match: MatchResult,
        name: String,
        visibility: String,
        file: String,
        module: String,
        lineStarts: List<Int>,
        kind: SymbolKind,
    ): SymbolLocation {
        val offset = match.range.first + match.value.indexOf(name)
        val line = lineStarts.count { it <= offset }
        val column = offset - (lineStarts.getOrElse(line - 1) { 0 }) + 1
        return SymbolLocation(name, kind, file, line, column, module, visibility.ifBlank { null })
    }

    private fun String.toKind(): SymbolKind = when (this) {
        "class" -> SymbolKind.CLASS
        "interface" -> SymbolKind.INTERFACE
        "enum" -> SymbolKind.ENUM
        else -> SymbolKind.ANNOTATION
    }

    private fun stripComments(text: String): String =
        text.replace(Regex("""(?s)/\*.*?\*/""")) { " ".repeat(it.value.length) }
            .replace(Regex("""//.*""")) { " ".repeat(it.value.length) }

    private fun lineStarts(text: String): List<Int> = buildList {
        add(0)
        text.forEachIndexed { index, char ->
            if (char == '\n') add(index + 1)
        }
    }

    private val javaKeywords = setOf("if", "for", "while", "switch", "catch", "return", "new")
}
