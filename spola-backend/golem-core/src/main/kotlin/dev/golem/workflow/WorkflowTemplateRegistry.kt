package dev.spola.workflow

import dev.spola.GolemConfig
import dev.spola.factory.WorkflowFactory
import dev.spola.workflow.yaml.YamlWorkflowLoader
import dev.tramai.orchestration.Workflow
import java.nio.file.Path

interface WorkflowTemplate {
    val name: String
    val version: String
    fun build(config: GolemConfig, goal: String, parametersJson: String): Workflow<GolemState, String>

    /**
     * Whether this template can contain composite steps referencing other workflows.
     * Templates with composite support must override [compileRecursive] to participate
     * in cycle detection.
     */
    val supportsRecursiveCompilation: Boolean
        get() = false

    /**
     * Compile this template with composite cycle tracking (parentsChain).
     * Called instead of [build] when [supportsRecursiveCompilation] is true.
     * Default falls back to [build] with empty params.
     */
    fun compileRecursive(
        config: GolemConfig,
        goal: String,
        registry: WorkflowTemplateRegistry,
        parentsChain: Set<String>,
    ): Workflow<GolemState, String> = build(config, goal, "{}")
}

class WorkflowTemplateRegistry {
    private val templates = linkedMapOf<String, WorkflowTemplate>()

    fun register(template: WorkflowTemplate) {
        templates[template.name] = template
    }

    fun resolve(name: String): WorkflowTemplate = templates[name] ?: error("Unknown workflow template: $name")

    fun buildWorkflow(
        name: String,
        config: GolemConfig,
        goal: String,
        parametersJson: String = "{}",
    ): Workflow<GolemState, String> {
        return resolve(name).build(config, goal, parametersJson)
    }

    fun list(): List<WorkflowTemplate> = templates.values.toList()

    /**
     * Discover and register YAML workflow definition files from a directory.
     * Call this after registering built-in templates to allow overrides.
     *
     * @param config Golem configuration (used for default paths)
     * @param customDir Optional custom directory to scan (default: ~/.golem/workflows/)
     */
    fun registerYamlWorkflows(config: GolemConfig, customDir: Path? = null) {
        YamlWorkflowLoader.loadAndRegister(this, config, customDir)
    }
}

fun WorkflowTemplateRegistry.registerBuiltInTemplates() {
    register(object : WorkflowTemplate {
        override val name: String = "code-review"
        override val version: String = "1"

        override fun build(config: GolemConfig, goal: String, parametersJson: String): Workflow<GolemState, String> =
            WorkflowFactory.createWorkflow(
                name = "code-review-team",
                definitionVersion = version,
                workflow = {
                    with(TeamWorkflowSteps) {
                        val reviewers = listOf("security-reviewer", "style-reviewer", "test-reviewer")
                        parallelAgentsStep(
                            name = "parallel-review",
                            agents = reviewers,
                            goal = { it.goal },
                            config = config,
                            merge = { state, results ->
                                state.copy(
                                    intermediateResults = state.intermediateResults +
                                        reviewers.zip(results).associate { (id, result) -> id to result },
                                )
                            },
                        )
                    }
                    aiStep(
                        name = "summarize",
                        input = { state ->
                            val securityReview = state.intermediateResults["security-reviewer"] ?: ""
                            val styleReview = state.intermediateResults["style-reviewer"] ?: ""
                            val testReview = state.intermediateResults["test-reviewer"] ?: ""
                            """
                                |Aggregate the following code reviews into a concise final summary.
                                |
                                |## Security Review
                                |$securityReview
                                |
                                |## Style Review
                                |$styleReview
                                |
                                |## Test Review
                                |$testReview
                            """.trimMargin()
                        },
                        invoke = { it },
                        merge = { state, summary -> state.copy(result = summary) },
                    )
                },
                resultSelector = { it.result ?: "no result" },
            )
    })

    register(object : WorkflowTemplate {
        override val name: String = "jvm-debug"
        override val version: String = "1"

        override fun build(config: GolemConfig, goal: String, parametersJson: String): Workflow<GolemState, String> =
            JvmWorkflowTemplates.jvmDebugWorkflow(name = name, definitionVersion = version)
    })

    register(object : WorkflowTemplate {
        override val name: String = "jvm-refactor"
        override val version: String = "1"

        override fun build(config: GolemConfig, goal: String, parametersJson: String): Workflow<GolemState, String> =
            JvmWorkflowTemplates.jvmRefactorWorkflow(name = name, definitionVersion = version)
    })

    register(object : WorkflowTemplate {
        override val name: String = "jvm-migration"
        override val version: String = "1"

        override fun build(config: GolemConfig, goal: String, parametersJson: String): Workflow<GolemState, String> =
            JvmWorkflowTemplates.jvmMigrationWorkflow(name = name, definitionVersion = version)
    })
}
