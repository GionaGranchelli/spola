package dev.spola.jvm

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class RegexDebugTest {
    @Test
    fun `test kotlin regex matching`() {
        val regex = Regex("""(?:e: )?(.+\.(?:kt|java)):(\d+):(?:(\d+):)?\s*(?:error:)?\s*(.+)""")
        val line = "e: /repo/service/src/main/kotlin/com/example/service/Service.kt:12:17 Unresolved reference 'ApiModel'."
        val result = regex.find(line)
        println("Pattern raw: ${regex.pattern}")
        println("Pattern escaped: ${regex.pattern}")
        println("Match: $result")
        if (result != null) {
            println("Groups: ${result.groupValues}")
        }
        assertTrue(result != null, "Regex should match the Kotlin compilation error line")
    }
}
