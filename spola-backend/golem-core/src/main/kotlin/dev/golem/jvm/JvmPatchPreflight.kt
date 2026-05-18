package dev.spola.jvm

data class VerificationPlan(
    val compilationCommand: String,
    val testCommand: String,
    val estimatedDuration: String,
)

class JvmPatchPreflight {
    fun preflightCheck(
        changedFiles: List<String>,
        modules: List<ProjectModule>,
        depGraph: ModuleDependencyGraph,
    ): VerificationPlan {
        val changed = changedFiles.map { ChangedFile(it, ChangeType.MODIFIED) }
        val impact = ImpactAnalyzer().analyze(changed, modules, depGraph, emptyList())
        val compile = impact.compilationScope.ifEmpty { listOf("compileKotlin") }.joinToString(" ")
        val tests = TestSelectionEngine().selectTests(impact).ifEmpty { listOf("test") }.joinToString(" ")
        val duration = if (impact.impactedModules.size <= 1) "short" else "medium"
        return VerificationPlan(
            compilationCommand = "./gradlew $compile",
            testCommand = "./gradlew $tests",
            estimatedDuration = duration,
        )
    }
}
