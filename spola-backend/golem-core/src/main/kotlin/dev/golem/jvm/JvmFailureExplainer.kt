package dev.spola.jvm

data class FailureRootCause(
    val module: String?,
    val file: String?,
    val symbol: String?,
    val message: String,
)

data class FailureReport(
    val summary: String,
    val rootCauses: List<FailureRootCause>,
    val suggestedFixCommands: List<String>,
)

class JvmFailureExplainer {
    fun explain(
        failures: List<GradleFailure>,
        modules: List<ProjectModule>,
        symbols: List<SymbolLocation>,
    ): FailureReport {
        val rootCauses = failures
            .filter { it.type != GradleFailureType.TASK }
            .ifEmpty { failures }
            .map { failure ->
                val sameFile = symbols.filter { it.file == failure.file }
                val symbol = if (failure.line != null) {
                    sameFile.maxByOrNull { if (it.line <= failure.line) it.line else -1 }
                } else {
                    sameFile.firstOrNull()
                }
                val module = symbol?.module ?: failure.task?.moduleNameFromTask() ?: moduleForFile(failure.file, modules)
                FailureRootCause(
                    module = module,
                    file = failure.file,
                    symbol = symbol?.name ?: failure.testClass,
                    message = buildString {
                        append(failure.message)
                        val fix = fixSuggestion(failure.message)
                        if (fix != null) append(" Suggestion: $fix")
                        val hint = moduleHint(failure, modules)
                        if (hint != null) append(" $hint")
                    },
                )
            }
            .distinct()
        val commands = rootCauses.mapNotNull { it.module }.distinct().flatMap { module ->
            listOf(GradleTaskCataloger().suggestTasks(module, "src").first(), GradleTaskCataloger().suggestTestTask(module, null))
        }.distinct()
        return FailureReport(
            summary = if (rootCauses.isEmpty()) "No structured Gradle failures were found." else "${rootCauses.size} likely root cause(s) found.",
            rootCauses = rootCauses,
            suggestedFixCommands = commands,
        )
    }

    internal fun fixSuggestion(message: String): String? = when {
        message.contains("Unresolved reference") -> {
            val symbol = message.substringAfter("Unresolved reference").substringBefore(".").trim()
            "Missing import for '$symbol'. Add `import <package>.$symbol`."
        }
        message.contains("Type mismatch") -> {
            "Type mismatch — check the expected vs actual types in the expression."
        }
        message.contains("unresolved reference to") -> {
            "Missing or incorrect import."
        }
        message.contains("Only safe (?.) or non-null asserted") -> {
            "Use `?.` (safe call) or `!!` (assert non-null) operator."
        }
        message.contains("Cannot infer type") -> {
            "The compiler cannot infer the type. Add an explicit type annotation."
        }
        message.contains("does not have a constructor") -> {
            "The class '${extractClassName(message)}' is an interface or abstract class. It cannot be instantiated directly."
        }
        message.contains("expects") && message.contains("arguments") -> {
            "Wrong number of arguments. Check the function/constructor signature."
        }
        message.contains("must be initialized") -> {
            "Property '${extractPropertyName(message)}' must be initialized. Add `= defaultValue` or make it `lateinit`."
        }
        message.contains("cannot be cast") -> {
            "Class cast exception. The runtime type doesn't match the expected type."
        }
        message.contains("is not a member of") -> {
            "Method/property doesn't exist on this type. Check the type or method name."
        }
        else -> null
    }

    internal fun extractClassName(message: String): String =
        message.substringBefore("does not have a constructor")
            .substringAfterLast(" ")
            .trim()
            .removeSuffix("'")
            .removePrefix("'")
            .ifBlank { "Unknown" }

    internal fun extractPropertyName(message: String): String =
        message.substringBefore("must be initialized")
            .substringAfterLast(" ")
            .trim()
            .removeSuffix("'")
            .removePrefix("'")
            .ifBlank { "Unknown" }

    internal fun moduleHint(failure: GradleFailure, modules: List<ProjectModule>): String? {
        if (failure.file == null) return null
        val failingModule = moduleForFile(failure.file, modules) ?: return null
        // If the error mentions a type that might be in another module, add a hint
        if (failure.message.contains("Unresolved reference") || failure.message.contains("unresolved reference")) {
            val potentialType = failure.message
                .substringAfter("Unresolved reference")
                .substringAfter("unresolved reference to")
                .substringBefore(".")
                .trim()
                .removeSuffix("'")
                .removePrefix("'")
            if (potentialType.isNotBlank() && potentialType.length > 2) {
                return "Cross-module hint: '$potentialType' may be defined in another module. Check if module '$failingModule' has the correct dependency declared."
            }
        }
        return null
    }

    private fun String.moduleNameFromTask(): String? {
        val parts = split(":").filter { it.isNotBlank() }
        return if (parts.size > 1) ":${parts.dropLast(1).joinToString(":")}" else ":"
    }

    private fun moduleForFile(file: String?, modules: List<ProjectModule>): String? {
        val normalized = file?.replace('\\', '/') ?: return null
        val normalizedPath = ModuleDependencyGraph.modulePath(normalized)
        return modules.firstOrNull { module ->
            val prefix = ModuleDependencyGraph.modulePath(module.name)
            normalizedPath.startsWith("$prefix/") ||
                module.sourceDirs.any { normalized.startsWith(it.replace('\\', '/')) } ||
                module.testDirs.any { normalized.startsWith(it.replace('\\', '/')) }
        }?.name
    }
}
