package dev.spola.jvm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class GradleFailureParserTest {
    @Test
    fun `parses kotlin compilation failure`() {
        val output = """
            > Task :service:compileKotlin FAILED
            e: /repo/service/src/main/kotlin/com/example/service/Service.kt:12:17 Unresolved reference 'ApiModel'.
        """.trimIndent()

        val failures = GradleFailureParser().parse(output)

        assertTrue(failures.any { it.type == GradleFailureType.TASK && it.task == ":service:compileKotlin" })
        val compile = failures.first { it.type == GradleFailureType.COMPILATION }
        assertEquals("/repo/service/src/main/kotlin/com/example/service/Service.kt", compile.file)
        assertEquals(12, compile.line)
        assertTrue(compile.message.contains("Unresolved reference"))
    }

    @Test
    fun `parses junit test failure`() {
        val output = """
            > Task :app:test FAILED
            com.example.AppTest > renders value FAILED
                org.opentest4j.AssertionFailedError: expected true
                    at com.example.AppTest.renders(AppTest.kt:14)
        """.trimIndent()

        val failures = GradleFailureParser().parse(output)
        val test = failures.first { it.type == GradleFailureType.TEST }

        assertEquals("com.example.AppTest", test.testClass)
        assertEquals("renders value", test.testMethod)
        assertTrue(test.message.contains("AssertionFailedError"))
        assertTrue(test.stackTraceRoot!!.contains("AppTest.kt:14"))
    }

    @Test
    fun `parses configuration phase error`() {
        val output = """
            * What went wrong:
            Could not resolve all dependencies for configuration ':app:runtimeClasspath'.
            > Could not find org.example:missing:1.0.0.
        """.trimIndent()

        val failures = GradleFailureParser().parse(output)
        assertTrue(failures.any { it.type == GradleFailureType.CONFIGURATION })
        val config = failures.first { it.type == GradleFailureType.CONFIGURATION }
        assertTrue(config.message.contains("Could not resolve"))
    }

    @Test
    fun `parses dependency resolution error`() {
        val output = """
            > Task :app:compileKotlin FAILED
            Could not resolve com.example:library:2.0.0.
        """.trimIndent()

        val failures = GradleFailureParser().parse(output)
        assertTrue(failures.any { it.type == GradleFailureType.DEPENDENCY_RESOLUTION })
    }

    @Test
    fun `parses daemon error`() {
        val output = """
            Could not start Gradle daemon.
        """.trimIndent()

        val failures = GradleFailureParser().parse(output)
        assertTrue(failures.any { it.type == GradleFailureType.DAEMON })
    }

    @Test
    fun `parses junit test failure with expected vs actual and assertion method`() {
        val output = """
            > Task :app:test FAILED
            com.example.CalcTest > add should return sum FAILED
                org.opentest4j.AssertionFailedError: expected: <5> but was: <3>
                    at com.example.CalcTest.add should return sum(CalcTest.kt:25)
        """.trimIndent()

        val failures = GradleFailureParser().parse(output)
        val test = failures.first { it.type == GradleFailureType.TEST }

        assertEquals("com.example.CalcTest", test.testClass)
        assertEquals("add should return sum", test.testMethod)
        assertEquals("5", test.expectedValue)
        assertEquals("3", test.actualValue)
        assertEquals("assertEquals", test.assertionMethod)
    }

    @Test
    fun `detects test failures summary line`() {
        val output = """
            Tests failed: 2 of 10 tests
        """.trimIndent()

        val failures = GradleFailureParser().parse(output)
        assertTrue(failures.any { it.type == GradleFailureType.TEST })
    }
}
