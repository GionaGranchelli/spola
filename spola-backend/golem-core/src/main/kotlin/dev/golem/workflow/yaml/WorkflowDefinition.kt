package dev.spola.workflow.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Root YAML workflow definition.
 * Parsed from ~/.golem/workflows/<name>.yaml
 */
data class WorkflowDefinition(
    val name: String,
    val version: String = "1",
    val description: String = "",
    val params: Map<String, ParamDef> = emptyMap(),
    val steps: List<StepDef> = emptyList(),
    val done: List<DoneCondition> = emptyList(),
)

/**
 * Parameter definition for a workflow.
 */
data class ParamDef(
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false,
    val default: Any? = null,
)

enum class OnError {
    FAIL,
    CONTINUE;

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromString(value: String): OnError = valueOf(value.uppercase())
    }
}

/**
 * A single step in the workflow DAG.
 */
data class StepDef(
    val id: String,
    val type: String,
    val goal: String = "",
    val persona: String? = null,
    val agents: List<String>? = null,
    @get:JsonProperty("depends_on")
    val dependsOn: List<String>? = null,
    val command: String? = null,
    val timeout: Int = 60,
    val prompt: String? = null,
    val expression: String? = null,
    @get:JsonProperty("workflow_ref")
    val workflowRef: String? = null,
    val invoke: String? = null,
    val done: List<DoneCondition> = emptyList(),
    @get:JsonProperty("on_error")
    val onError: OnError = OnError.FAIL,
    @get:JsonProperty("retry_count")
    val retryCount: Int = 0,
    @get:JsonProperty("max_output_bytes")
    val maxOutputBytes: Long = 10 * 1024 * 1024,
    val env: Map<String, String>? = null,
)

/**
 * A Definition of Done condition.
 */
data class DoneCondition(
    val condition: String,
    val value: String? = null,
)

/**
 * Parsed and resolved workflow ready for compilation.
 * {{params.X}} and {{state.X}} templates have been resolved.
 */
data class ResolvedWorkflow(
    val name: String,
    val version: String,
    val description: String,
    val params: Map<String, Any?>,
    val steps: List<ResolvedStep>,
    val done: List<DoneCondition>,
)

/**
 * A step with all templates resolved.
 */
data class ResolvedStep(
    val id: String,
    val type: String,
    val goal: String,
    val persona: String?,
    val agents: List<String>?,
    val dependsOn: List<String>?,
    val command: String?,
    val timeout: Int,
    val prompt: String?,
    val expression: String?,
    val workflowRef: String?,
    val invoke: String?,
    val done: List<DoneCondition>,
    val onError: OnError,
    val retryCount: Int,
    val maxOutputBytes: Long,
    val env: Map<String, String>?,
)
