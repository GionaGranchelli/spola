package dev.spola.workflow.yaml

import org.slf4j.LoggerFactory

/**
 * Resolves `{{variable}}` template syntax in workflow definition strings.
 *
 * Supported variables:
 * - `{{params.X}}` — Parameter values passed at runtime
 * - `{{state.goal}}` — The user's goal string
 * - `{{step.<id>.output}}` — Output of a previous step (resolved at runtime via GolemState)
 *
 * This is a simple regex-based resolver. No external templating library needed.
 */
object WorkflowParameterResolver {

    private val logger = LoggerFactory.getLogger(WorkflowParameterResolver::class.java)

    // Pattern matches {{...}} but not ${{...}}
    private val templatePattern = Regex("""\{\{(.+?)}}""")

    /**
     * Resolve all template variables in a [WorkflowDefinition] with concrete parameter values.
     * Returns a [ResolvedWorkflow] with all templates expanded.
     */
    fun resolve(
        definition: WorkflowDefinition,
        runtimeParams: Map<String, Any?>,
        goal: String = "",
    ): ResolvedWorkflow {
        val params = resolveParams(definition.params, runtimeParams)

        val resolvedSteps = definition.steps.map { step ->
            ResolvedStep(
                id = step.id,
                type = step.type,
                goal = resolveTemplates(step.goal, params, goal, null),
                persona = step.persona?.let { resolveTemplates(it, params, goal, null) },
                agents = step.agents?.map { resolveTemplates(it, params, goal, null) },
                dependsOn = step.dependsOn,
                command = step.command?.let { resolveTemplates(it, params, goal, null) },
                timeout = step.timeout,
                prompt = step.prompt?.let { resolveTemplates(it, params, goal, null) },
                expression = step.expression,
                workflowRef = step.workflowRef,
                invoke = step.invoke,
                done = step.done,
                onError = step.onError,
                retryCount = step.retryCount,
                maxOutputBytes = step.maxOutputBytes,
                env = step.env?.mapValues { (_, value) ->
                    resolveTemplates(value, params, goal, null)
                },
            )
        }

        return ResolvedWorkflow(
            name = resolveTemplates(definition.name, params, goal, null),
            version = definition.version,
            description = resolveTemplates(definition.description, params, goal, null),
            params = params,
            steps = resolvedSteps,
            done = definition.done,
        )
    }

    /**
     * Merge runtime params with defaults from the definition.
     */
    private fun resolveParams(
        paramDefs: Map<String, ParamDef>,
        runtimeParams: Map<String, Any?>,
    ): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for ((key, def) in paramDefs) {
            val value = runtimeParams[key] ?: def.default
            if (value != null) {
                result[key] = value
            } else if (def.required) {
                logger.warn("Required parameter '{}' is missing, using empty string", key)
                result[key] = ""
            }
        }
        // Extra runtime params not in definition are still included
        result.putAll(runtimeParams)
        return result
    }

    /**
     * Resolve `{{params.X}}` and `{{state.goal}}` in a template string.
     * `{{step.X.output}}` is preserved for runtime resolution.
     */
    fun resolveTemplates(
        template: String,
        params: Map<String, Any?>,
        goal: String,
        stepOutputs: Map<String, String>?,
    ): String {
        return templatePattern.replace(template) { match ->
            val expression = match.groupValues[1].trim()
            resolveExpression(expression, params, goal, stepOutputs) ?: match.value
        }
    }

    /**
     * Resolve a single template expression.
     * Returns the resolved value or null if unresolvable (preserves original).
     */
    private fun resolveExpression(
        expression: String,
        params: Map<String, Any?>,
        goal: String,
        stepOutputs: Map<String, String>?,
    ): String? {
        return when {
            expression == "state.goal" -> goal
            expression.startsWith("params.") -> {
                val key = expression.removePrefix("params.")
                params[key]?.toString()
            }
            expression.startsWith("step.") && expression.endsWith(".output") -> {
                // Runtime resolution: extract step ID, look up in state at execution time
                // The placeholder is preserved for runtime lookup
                null // Return null → keep {{step.X.output}} in template for runtime
            }
            expression.startsWith("state.") -> {
                // Other state fields resolved at runtime
                null
            }
            else -> null
        }
    }

    /**
     * Runtime-only resolution for variables that depend on workflow state.
     * Called during step execution, not at compile time.
     */
    fun resolveRuntimeTemplates(
        template: String,
        stepOutputs: Map<String, String>,
    ): String {
        return templatePattern.replace(template) { match ->
            val expression = match.groupValues[1].trim()
            when {
                expression.startsWith("step.") && expression.endsWith(".output") -> {
                    val stepId = expression.removePrefix("step.").removeSuffix(".output")
                    stepOutputs[stepId] ?: match.value
                }
                else -> match.value // Keep as-is if not resolvable
            }
        }
    }
}
