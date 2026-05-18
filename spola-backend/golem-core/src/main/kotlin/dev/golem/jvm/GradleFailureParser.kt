package dev.spola.jvm

enum class GradleFailureType {
    TASK,
    COMPILATION,
    TEST,
    CONFIGURATION,
    DEPENDENCY_RESOLUTION,
    DAEMON,
}

data class GradleFailure(
    val type: GradleFailureType,
    val task: String? = null,
    val file: String? = null,
    val line: Int? = null,
    val message: String,
    val testClass: String? = null,
    val testMethod: String? = null,
    val stackTraceRoot: String? = null,
    val expectedValue: String? = null,
    val actualValue: String? = null,
    val assertionMethod: String? = null,
)

class GradleFailureParser {
    private val ansiRegex = Regex("""\u001B\[[;\d]*m""")
    private val taskRegex = Regex("""> Task (:[^\s]+) FAILED""")
    private val kotlinErrorRegex = Regex("""(?:e: )?(.+\.(?:kt|java)):(\d+):(?:(\d+):)?\s*(?:error:)?\s*(.+)""")
    private val javaErrorRegex = Regex("""(.+\.java):(\d+):\s*error:\s*(.+)""")
    private val junitRegex = Regex("""^([A-Za-z0-9_.$]+)\s*>\s*(.+?)\s+FAILED$""")
    private val configurationErrorRegex = Regex("""^\*\s*What went wrong:""")
    private val dependencyResolutionRegex = Regex("""(Could not resolve|Failed to resolve)\s+""")
    private val daemonErrorRegex = Regex("""(Could not start Gradle daemon|Daemon is busy|Gradle daemon stopped)""")
    private val testFailuresRegex = Regex("""(Tests failed:|Test failures:)""")
    private val assertionRegex = Regex(
        """expected:\s*(<[^>]+>|[^<]+)\s*but\s+was:\s*(<[^>]+>|[^<]+)"""
    )
    private val junitLineRegex = Regex(
        """at\s+(?:.+\\.)?(.+Test)\.(\w+)\((.+\.kt?:\d+)\)"""
    )

    fun parse(output: String): List<GradleFailure> {
        val lines = output.replace(ansiRegex, "").lines()
        val failures = mutableListOf<GradleFailure>()
        var currentTask: String? = null
        lines.forEachIndexed { index, line ->
            taskRegex.find(line)?.let {
                currentTask = it.groupValues[1]
                failures += GradleFailure(GradleFailureType.TASK, task = currentTask, message = "Task ${it.groupValues[1]} failed")
            }
            javaErrorRegex.find(line)?.let {
                failures += GradleFailure(
                    type = GradleFailureType.COMPILATION,
                    task = currentTask,
                    file = it.groupValues[1].trim(),
                    line = it.groupValues[2].toIntOrNull(),
                    message = it.groupValues[3].trim(),
                )
                return@forEachIndexed
            }
            kotlinErrorRegex.find(line)?.let {
                failures += GradleFailure(
                    type = GradleFailureType.COMPILATION,
                    task = currentTask,
                    file = it.groupValues[1].trim(),
                    line = it.groupValues[2].toIntOrNull(),
                    message = it.groupValues[4].trim(),
                )
                return@forEachIndexed
            }

            // Configuration phase error detection
            if (configurationErrorRegex.containsMatchIn(line)) {
                val messageLines = lines.drop(index + 1).takeWhile { it.isNotBlank() && !it.startsWith("* ") }
                val configMessage = messageLines.firstOrNull { it.isNotBlank() }?.trim() ?: "Configuration failed"
                failures += GradleFailure(
                    type = GradleFailureType.CONFIGURATION,
                    task = currentTask,
                    message = configMessage,
                )
                return@forEachIndexed
            }

            // Dependency resolution error detection
            dependencyResolutionRegex.find(line)?.let {
                failures += GradleFailure(
                    type = GradleFailureType.DEPENDENCY_RESOLUTION,
                    task = currentTask,
                    message = line.trim(),
                )
                return@forEachIndexed
            }

            // Daemon error detection
            daemonErrorRegex.find(line)?.let {
                failures += GradleFailure(
                    type = GradleFailureType.DAEMON,
                    task = currentTask,
                    message = line.trim(),
                )
                return@forEachIndexed
            }

            // Test failure summary detection
            testFailuresRegex.find(line)?.let {
                failures += GradleFailure(
                    type = GradleFailureType.TEST,
                    task = currentTask,
                    message = line.trim(),
                )
                return@forEachIndexed
            }

            // JUnit test failure parsing with enhanced details
            junitRegex.find(line.trim())?.let {
                val nextLine = nextNonBlank(lines, index + 1)
                val assertionInfo = if (nextLine != null) {
                    val expected = assertionRegex.find(nextLine)?.let { m ->
                        val raw = m.groupValues[1].trim()
                        if (raw.startsWith("<") && raw.endsWith(">")) raw.substring(1, raw.length - 1) else raw
                    }
                    val actual = assertionRegex.find(nextLine)?.let { m ->
                        val raw = m.groupValues[2].trim()
                        if (raw.startsWith("<") && raw.endsWith(">")) raw.substring(1, raw.length - 1) else raw
                    }
                    val assertionMethod = when {
                        nextLine.contains("assertEquals") -> "assertEquals"
                        nextLine.contains("assertTrue") -> "assertTrue"
                        nextLine.contains("assertFalse") -> "assertFalse"
                        nextLine.contains("assertNull") -> "assertNull"
                        nextLine.contains("assertNotNull") -> "assertNotNull"
                        nextLine.contains("assertSame") -> "assertSame"
                        nextLine.contains("assertNotSame") -> "assertNotSame"
                        else -> null
                    }
                    Triple(expected, actual, assertionMethod)
                } else null

                val stackLine = lines.drop(index + 1).firstOrNull { stack -> stack.trim().startsWith("at ") }?.trim()
                failures += GradleFailure(
                    type = GradleFailureType.TEST,
                    task = currentTask,
                    message = nextLine ?: "Test failed",
                    testClass = it.groupValues[1],
                    testMethod = it.groupValues[2],
                    stackTraceRoot = stackLine,
                    expectedValue = assertionInfo?.first,
                    actualValue = assertionInfo?.second,
                    assertionMethod = assertionInfo?.third ?: (if (nextLine?.contains("AssertionFailedError") == true) "assertEquals" else null),
                )
            }
        }
        return failures.distinct()
    }

    private fun nextNonBlank(lines: List<String>, start: Int): String? =
        lines.drop(start).firstOrNull { it.isNotBlank() }?.trim()
}
