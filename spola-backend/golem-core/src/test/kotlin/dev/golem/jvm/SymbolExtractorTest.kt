package dev.spola.jvm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SymbolExtractorTest {
    @Test
    fun `kotlin extractor finds declarations visibility and multiline signatures`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("Sample.kt")
        Files.writeString(file, """
            package sample

            public class Alpha
            internal object Beta
            private interface Gamma
            enum class Mode { A }
            annotation class Marker
            fun topLevel(
                value: String,
            ): String = value
            val answer = 42
            var count = 0
        """.trimIndent())

        val symbols = KotlinSymbolExtractor().extract(file, ":app", tempDir)

        assertTrue(symbols.any { it.name == "Alpha" && it.kind == SymbolKind.CLASS && it.visibility == "public" })
        assertTrue(symbols.any { it.name == "Beta" && it.kind == SymbolKind.OBJECT && it.visibility == "internal" })
        assertTrue(symbols.any { it.name == "Gamma" && it.kind == SymbolKind.INTERFACE && it.visibility == "private" })
        assertTrue(symbols.any { it.name == "Mode" && it.kind == SymbolKind.ENUM })
        assertTrue(symbols.any { it.name == "Marker" && it.kind == SymbolKind.ANNOTATION })
        assertTrue(symbols.any { it.name == "topLevel" && it.kind == SymbolKind.FUN })
        assertTrue(symbols.any { it.name == "answer" && it.kind == SymbolKind.VAL })
        assertTrue(symbols.any { it.name == "count" && it.kind == SymbolKind.VAR })
    }

    @Test
    fun `java extractor finds class interface method and field`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("Sample.java")
        Files.writeString(file, """
            package sample;

            public class Sample {
                private String field = "";
                public String value() {
                    return field;
                }
            }
            interface Worker {}
            enum Mode { A }
        """.trimIndent())

        val symbols = JavaSymbolExtractor().extract(file, ":app", tempDir)

        assertTrue(symbols.any { it.name == "Sample" && it.kind == SymbolKind.CLASS && it.visibility == "public" })
        assertTrue(symbols.any { it.name == "Worker" && it.kind == SymbolKind.INTERFACE })
        assertTrue(symbols.any { it.name == "Mode" && it.kind == SymbolKind.ENUM })
        assertTrue(symbols.any { it.name == "value" && it.kind == SymbolKind.FUN && it.visibility == "public" })
        assertTrue(symbols.any { it.name == "field" && it.kind == SymbolKind.VAL && it.visibility == "private" })
    }

    @Test
    fun `empty and comments only files return no symbols`(@TempDir tempDir: Path) {
        val empty = tempDir.resolve("Empty.kt")
        val comments = tempDir.resolve("Comments.java")
        Files.writeString(empty, "")
        Files.writeString(comments, "// class Nope\n/* fun no() {} */")

        assertEquals(emptyList(), KotlinSymbolExtractor().extract(empty, ":app", tempDir))
        assertEquals(emptyList(), JavaSymbolExtractor().extract(comments, ":app", tempDir))
    }
}
