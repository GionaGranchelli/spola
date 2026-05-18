package dev.spola.jvm

object JvmPlanningPrompts {
    fun planningPrompt(goal: String, contextPack: String?, impactReport: ImpactReport?): String = buildString {
        appendLine(goal)
        if (!contextPack.isNullOrBlank()) {
            appendLine()
            appendLine("## JVM Project Context")
            appendLine(contextPack)
        }
        if (impactReport != null && impactReport.impactedModules.isNotEmpty()) {
            appendLine()
            appendLine("## Current Change Impact")
            appendLine("Impacted modules: ${impactReport.impactedModules.joinToString(", ")}")
            appendLine("Verification: ${impactReport.verificationCommands.joinToString(" && ")}")
        }
    }.trim()

    fun implementationBrief(goal: String, plan: String, dependencyTrace: String?): String = buildString {
        appendLine(goal)
        appendLine()
        appendLine("## Architect Plan")
        appendLine(plan)
        if (!dependencyTrace.isNullOrBlank()) {
            appendLine()
            appendLine("## JVM Dependency Trace")
            appendLine(dependencyTrace)
        }
    }.trim()
}
