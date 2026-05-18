package dev.spola.workflow.yaml

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.spola.workflow.WorkflowTemplate
import dev.spola.workflow.WorkflowTemplateRegistry

/**
 * Exports existing Kotlin workflow templates to YAML format.
 *
 * This enables the 'golem workflow export <name>' command:
 * converts a hardcoded template into a portable YAML file that
 * can be edited and placed in ~/.golem/workflows/.
 */
object WorkflowExport {

    private val yamlMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .writerWithDefaultPrettyPrinter()

    /**
     * Export a named workflow template from the registry as a YAML string.
     *
     * Since we can't fully reverse-engineer the Kotlin DSL at runtime,
     * this generates a best-effort YAML template from the known built-in
     * templates, with placeholders the user can customize.
     */
    fun exportTemplate(registry: WorkflowTemplateRegistry, name: String): String? {
        val template = try {
            registry.resolve(name)
        } catch (e: IllegalStateException) {
            return null
        }

        return when (name) {
            "code-review" -> exportCodeReviewYaml()
            "jvm-debug" -> exportJvmDebugYaml()
            "jvm-refactor" -> exportJvmRefactorYaml()
            "jvm-migration" -> exportJvmMigrationYaml()
            else -> generateGenericYaml(name, template)
        }
    }

    /**
     * Export the code-review template as YAML.
     */
    private fun exportCodeReviewYaml(): String = """
name: code-review
version: "1"
description: "Multi-reviewer code review with parallel agents and summary"
params:
  files:
    type: string
    description: "File glob to review"
    default: "**/*.kt"
  reviewers:
    type: array
    description: "List of reviewer agent IDs"
    default: ["security-reviewer", "style-reviewer", "test-reviewer"]

steps:
  - id: parallel-review
    type: parallel_agents
    agents: "{{params.reviewers}}"
    goal: "Review code changes for quality and correctness: {{params.files}}. Goal: {{state.goal}}"
    persona: "You are a thorough code reviewer."
    done:
      - condition: all_agents_completed
      - condition: output_has_content

  - id: summarize
    type: ai
    depends_on: [parallel-review]
    goal: |
      Aggregate the following parallel code reviews into a concise final summary.
      Identify cross-cutting concerns and prioritize findings.

      Review results:
      {{step.parallel-review.output}}

      Original goal: {{state.goal}}
    persona: "You are a technical editor specializing in code review aggregation."
    done:
      - condition: output_has_content
      - condition: markdown_valid
      - condition: output_contains "CRITICAL|HIGH|MEDIUM|LOW|INFO"

done:
  - condition: all_steps_passed
  - condition: report_generated
""".trimIndent()

    /**
     * Export the jvm-debug template as YAML.
     */
    private fun exportJvmDebugYaml(): String = """
name: jvm-debug
version: "1"
description: "Diagnose and fix JVM build or test failures"
params:
  target:
    type: string
    description: "Class, test, or module to debug"
    default: ""

steps:
  - id: scan-and-diagnose
    type: ai
    goal: |
      Diagnose JVM build or test failures for the goal: {{state.goal}}{{#params.target}}

      Focus on the target: {{params.target}}{{/params.target}}

      Use jvm_project_overview, jvm_symbol_search, jvm_failure_explain, jvm_change_impact, and jvm_verify_plan tools.
    persona: "You are a JVM debugging specialist focused on Kotlin/Gradle repositories."
    done:
      - condition: output_has_content

  - id: fix-and-verify
    type: ai
    depends_on: [scan-and-diagnose]
    goal: |
      Continue the JVM debugging task.
      Use the earlier diagnostics and finish with the exact compile/test verification commands.

      Prior diagnostics: {{step.scan-and-diagnose.output}}
      Goal: {{state.goal}}
    persona: "You are a JVM debugging specialist focused on Kotlin/Gradle repositories."
    done:
      - condition: output_has_content
      - condition: output_contains "compile|test|verify"

done:
  - condition: all_steps_passed
  - condition: no_critical_blockers
""".trimIndent()

    /**
     * Export the jvm-refactor template as YAML.
     */
    private fun exportJvmRefactorYaml(): String = """
name: jvm-refactor
version: "1"
description: "Analyze and execute JVM code refactoring across Gradle modules"
params:
  module:
    type: string
    description: "Target module for refactoring"
    default: ""

steps:
  - id: overview-and-impact
    type: ai
    goal: |
      Analyze a JVM refactor request. Inspect project structure, assess impact, and propose safe edits.
      Use jvm_project_overview, jvm_dependency_trace, jvm_symbol_search, jvm_change_impact, and jvm_verify_plan.

      Goal: {{state.goal}}{{#params.module}}

      Target module: {{params.module}}{{/params.module}}
    persona: "You are a JVM refactoring specialist for multi-module Gradle codebases."
    done:
      - condition: output_has_content

  - id: plan-and-verify
    type: ai
    depends_on: [overview-and-impact]
    goal: |
      Produce a concrete JVM refactor plan using the project overview and impact context already gathered.
      Finish with impacted modules and verification commands.

      Impact analysis: {{step.overview-and-impact.output}}
      Goal: {{state.goal}}
    persona: "You are a JVM refactoring specialist for multi-module Gradle codebases."
    done:
      - condition: output_has_content
      - condition: markdown_valid

done:
  - condition: all_steps_passed
  - condition: report_generated
""".trimIndent()

    /**
     * Export the jvm-migration template as YAML.
     */
    private fun exportJvmMigrationYaml(): String = """
name: jvm-migration
version: "1"
description: "Plan and execute a JVM dependency or framework migration"
params:
  source:
    type: string
    description: "Source dependency/version"
    default: ""
  target:
    type: string
    description: "Target dependency/version"
    default: ""

steps:
  - id: catalog-and-window
    type: ai
    goal: |
      Catalog usages, identify migration window, and plan the migration.
      Use jvm_project_overview, jvm_dependency_trace, jvm_context_pack, and jvm_verify_plan.

      Goal: {{state.goal}}{{#params.source}}

      Source: {{params.source}}{{/params.source}}{{#params.target}}

      Target: {{params.target}}{{/params.target}}
    persona: "You are a JVM migration specialist focused on safe, incremental upgrades."
    done:
      - condition: output_has_content

  - id: module-apply-and-verify
    type: ai
    depends_on: [catalog-and-window]
    goal: |
      Continue the JVM migration. Break the work down per module, note migration risks, and finish with
      compile/test commands required to validate the migration.

      Migration plan: {{step.catalog-and-window.output}}
      Goal: {{state.goal}}
    persona: "You are a JVM migration specialist focused on safe, incremental upgrades."
    done:
      - condition: output_has_content
      - condition: markdown_valid

done:
  - condition: all_steps_passed
  - condition: report_generated
  - condition: no_critical_blockers
""".trimIndent()

    /**
     * Generate a generic YAML template for unknown workflow templates.
     */
    private fun generateGenericYaml(name: String, template: WorkflowTemplate): String {
        return """
name: $name
version: "${template.version}"
description: "Exported from built-in template '$name'"
params:
  target:
    type: string
    description: "Primary target for this workflow"
    default: ""

steps:
  - id: step-1
    type: ai
    goal: "Execute the $name workflow. Goal: {{state.goal}}"
    persona: "You are an expert assistant."
    done:
      - condition: output_has_content

done:
  - condition: all_steps_passed
""".trimIndent()
    }
}
