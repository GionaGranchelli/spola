package dev.spola.workflow

import dev.spola.SpolaConfig
import dev.spola.workflow.yaml.YamlWorkflowLoader
import dev.tramai.orchestration.Workflow
import java.nio.file.Path

interface WorkflowTemplate {
    val name: String
    val version: String
    fun build(config: SpolaConfig, goal: String, parametersJson: String): Workflow<SpolaState, String>

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
        config: SpolaConfig,
        goal: String,
        registry: WorkflowTemplateRegistry,
        parentsChain: Set<String>,
    ): Workflow<SpolaState, String> = build(config, goal, "{}")
}

class WorkflowTemplateRegistry {
    private val templates = linkedMapOf<String, WorkflowTemplate>()

    fun register(template: WorkflowTemplate) {
        templates[template.name] = template
    }

    fun resolve(name: String): WorkflowTemplate = templates[name] ?: error("Unknown workflow template: $name")

    fun buildWorkflow(
        name: String,
        config: SpolaConfig,
        goal: String,
        parametersJson: String = "{}",
    ): Workflow<SpolaState, String> {
        return resolve(name).build(config, goal, parametersJson)
    }

    fun list(): List<WorkflowTemplate> = templates.values.toList()

    /**
     * Discover and register YAML workflow definition files from a directory.
     * Call this after registering built-in templates to allow overrides.
     *
     * @param config Spola configuration (used for default paths)
     * @param customDir Optional custom directory to scan (default: ~/.spola/workflows/)
     */
    @Deprecated("Use TramAI workflow { } DSL instead")
    fun registerYamlWorkflows(config: SpolaConfig, customDir: Path? = null) {
        if (!config.yamlWorkflowsEnabled) return
        YamlWorkflowLoader.loadAndRegister(this, config, customDir)
    }
}

fun WorkflowTemplateRegistry.registerBuiltInTemplates() {
    // Built-in templates are removed — use agents directly or create YAML workflows.
    // Previous built-ins: code-review, jvm-debug, jvm-refactor, jvm-migration.
}
