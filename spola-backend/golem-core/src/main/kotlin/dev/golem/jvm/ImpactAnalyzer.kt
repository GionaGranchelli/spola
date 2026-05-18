package dev.spola.jvm

data class ImpactReport(
    val changedFiles: List<ChangedFile>,
    val changedSymbols: List<SymbolLocation>,
    val impactedModules: List<String>,
    val likelyAffectedTests: List<String>,
    val compilationScope: List<String>,
    val verificationCommands: List<String>,
)

class ImpactAnalyzer {
    fun analyze(
        changedFiles: List<ChangedFile>,
        modules: List<ProjectModule>,
        depGraph: ModuleDependencyGraph,
        symbols: List<SymbolLocation>,
    ): ImpactReport {
        val impactedModules = depGraph.findAffectedModules(changedFiles.map { it.path }, modules, symbols)
        val taskCataloger = GradleTaskCataloger()
        val changedTestClasses = changedFiles.mapNotNull { it.path.toTestClassName() }
        val compilationScope = impactedModules.flatMap { module ->
            val hasJava = changedFiles.any { it.path.endsWith(".java") && moduleOwnsPath(module, it.path) }
            taskCataloger.suggestTasks(module, if (hasJava) "java" else "src")
        }.distinct()
        val likelyTests = if (changedTestClasses.isNotEmpty()) {
            impactedModules.flatMap { module -> changedTestClasses.map { taskCataloger.suggestTestTask(module, it) } }
        } else {
            impactedModules.map { taskCataloger.suggestTestTask(it, null) }
        }.distinct()
        return ImpactReport(
            changedFiles = changedFiles,
            changedSymbols = symbols,
            impactedModules = impactedModules,
            likelyAffectedTests = likelyTests,
            compilationScope = compilationScope,
            verificationCommands = (compilationScope + likelyTests).distinct(),
        )
    }

    private fun moduleOwnsPath(module: String, path: String): Boolean {
        val normalized = path.replace('\\', '/')
        if (module == ":") return true
        val prefix = ModuleDependencyGraph.modulePath(module)
        return normalized == prefix || normalized.startsWith("$prefix/")
    }
}

class TestSelectionEngine {
    fun selectTests(impact: ImpactReport): List<String> =
        impact.likelyAffectedTests.ifEmpty { impact.impactedModules.map { GradleTaskCataloger().suggestTestTask(it, null) } }
}

private fun String.toTestClassName(): String? {
    if (!contains("/src/test/") || !(endsWith("Test.kt") || endsWith("Test.java"))) return null
    return substringAfter("/src/test/")
        .substringAfter("kotlin/", missingDelimiterValue = substringAfter("/src/test/").substringAfter("java/"))
        .removeSuffix(".kt")
        .removeSuffix(".java")
        .replace('/', '.')
        .takeIf { it.isNotBlank() }
}
