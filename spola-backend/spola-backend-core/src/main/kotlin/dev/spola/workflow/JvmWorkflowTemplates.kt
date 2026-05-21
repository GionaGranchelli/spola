package dev.spola.workflow

import dev.tramai.orchestration.Workflow
import dev.tramai.orchestration.workflow

object JvmWorkflowTemplates {
    fun jvmDebugWorkflow(
        name: String = "jvm-debug",
        definitionVersion: String = "1",
    ): Workflow<SpolaState, String> = workflow<SpolaState>(name, definitionVersion) {
        with(TeamWorkflowSteps) {
            jvmDebugStep("scan-and-diagnose")
            jvmDebugStep("fix-and-verify") { state ->
                """
                Continue the JVM debugging task. Use the earlier diagnostics in intermediateResults and finish with
                the exact compile/test verification commands you would run.
                Goal: ${state.goal}
                Prior results: ${state.intermediateResults}
                """.trimIndent()
            }
        }
    }.build { it.result ?: "no result" }

    fun jvmRefactorWorkflow(
        name: String = "jvm-refactor",
        definitionVersion: String = "1",
    ): Workflow<SpolaState, String> = workflow<SpolaState>(name, definitionVersion) {
        with(TeamWorkflowSteps) {
            jvmRefactorStep("overview-and-impact")
            jvmRefactorStep("plan-and-verify") { state ->
                """
                Produce a concrete JVM refactor plan using the project overview and impact context you already gathered.
                Finish with impacted modules and verification commands.
                Goal: ${state.goal}
                Prior results: ${state.intermediateResults}
                """.trimIndent()
            }
        }
    }.build { it.result ?: "no result" }

    fun jvmMigrationWorkflow(
        name: String = "jvm-migration",
        definitionVersion: String = "1",
    ): Workflow<SpolaState, String> = workflow<SpolaState>(name, definitionVersion) {
        with(TeamWorkflowSteps) {
            jvmMigrationStep("catalog-and-window")
            jvmMigrationStep("module-apply-and-verify") { state ->
                """
                Continue the JVM migration. Break the work down per module, note migration risks, and finish with
                compile/test commands required to validate the migration.
                Goal: ${state.goal}
                Prior results: ${state.intermediateResults}
                """.trimIndent()
            }
        }
    }.build { it.result ?: "no result" }
}
