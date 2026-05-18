package dev.spola.jvm

class GradleTaskCataloger {
    fun suggestTasks(module: String, changeType: String): List<String> {
        val prefix = if (module == ":" || module.isBlank()) "" else module
        return when (changeType.lowercase()) {
            "test" -> listOf(if (prefix.isBlank()) "test" else "$prefix:test")
            "build" -> listOf(if (prefix.isBlank()) "build" else "$prefix:build")
            "java" -> listOf(if (prefix.isBlank()) "compileJava" else "$prefix:compileJava")
            else -> listOf(if (prefix.isBlank()) "compileKotlin" else "$prefix:compileKotlin")
        }
    }

    fun suggestTestTask(module: String, testClass: String?): String {
        val task = if (module == ":" || module.isBlank()) "test" else "$module:test"
        return if (testClass.isNullOrBlank()) task else "$task --tests \"$testClass\""
    }
}
