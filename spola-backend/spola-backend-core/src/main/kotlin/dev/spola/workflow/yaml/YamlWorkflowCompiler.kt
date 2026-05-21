package dev.spola.workflow.yaml

import dev.spola.SpolaConfig
import dev.spola.factory.ProviderResolver
import dev.spola.workflow.TeamWorkflowSteps
import dev.spola.workflow.SpolaState
import dev.spola.factory.WorkflowFactory
import dev.spola.workflow.spolaAgentStep
import dev.spola.workflow.WorkflowTemplateRegistry
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.orchestration.GateDecision
import dev.tramai.orchestration.StopPolicy
import dev.tramai.orchestration.Workflow
import dev.tramai.orchestration.WorkflowBuilder
import dev.tramai.orchestration.WorkflowContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

/**
 * Compiles a resolved YAML workflow definition into a TramAI Workflow DAG.
 *
 * This is the bridge between declarative YAML and the existing Kotlin DSL.
 * It calls the SAME builder functions that the hardcoded templates use,
 * but generates them from parsed data instead of being written by hand.
 */
object YamlWorkflowCompiler {

    private val logger = LoggerFactory.getLogger(YamlWorkflowCompiler::class.java)

    /**
     * Compile a parsed and resolved workflow definition into a runnable TramAI workflow.
     */
    fun compile(
        resolved: ResolvedWorkflow,
        config: SpolaConfig,
        goal: String,
        registry: WorkflowTemplateRegistry,
        parentsChain: Set<String> = emptySet(),
    ): Workflow<SpolaState, String> {
        val activeChain = parentsChain + resolved.name
        return WorkflowFactory.createWorkflow(
            name = resolved.name,
            definitionVersion = resolved.version,
            stopPolicy = StopPolicy(maxStepExecutions = 50),
            workflow = {
                // Topologically sort steps by dependsOn
                val sortedSteps = YamlWorkflowDagSorter.sort(resolved.steps)

                // Check if any step or global done uses llm_judge condition
                val needsLlmJudge = sortedSteps.any { step ->
                    step.done.any { it.condition == "llm_judge" }
                } || resolved.done.any { it.condition == "llm_judge" }

                val llmJudgeProvider = if (needsLlmJudge) {
                    try {
                        ProviderResolver.resolveFromConfig(config)
                    } catch (e: Exception) {
                        logger.warn("LLM judge condition found but no provider configured: ${e.message}. llm_judge conditions will pass silently.")
                        null
                    }
                } else null

                for ((index, step) in sortedSteps.withIndex()) {
                    when (step.type) {
                        "ai" -> {
                            spolaAgentStep(
                                name = step.id,
                                persona = { step.persona ?: "You are a helpful assistant specialized in software development." },
                                goal = { state ->
                                    WorkflowParameterResolver.resolveRuntimeTemplates(
                                        step.goal, state.intermediateResults
                                    )
                                },
                                merge = { state, result ->
                                    state.copy(
                                        result = result,
                                        intermediateResults = state.intermediateResults + (step.id to result),
                                    )
                                },
                            )
                        }

                        "parallel_agents" -> {
                            val agentList = step.agents ?: emptyList()
                            with(TeamWorkflowSteps) {
                                parallelAgentsStep(
                                    name = step.id,
                                    agents = agentList,
                                    goal = { state ->
                                        WorkflowParameterResolver.resolveRuntimeTemplates(
                                            step.goal, state.intermediateResults
                                        )
                                    },
                                    config = config,
                                    merge = { state, results ->
                                        val combined = results.joinToString("\n---\n")
                                        state.copy(
                                            result = combined,
                                            intermediateResults = state.intermediateResults +
                                                agentList.zip(results).toMap(),
                                        )
                                    },
                                )
                            }
                        }

                        "human_approval" -> {
                            gateStep(name = step.id) { state, _ ->
                                if (state.intermediateResults["__approval_granted"] == "true") {
                                    GateDecision.allow()
                                } else {
                                    GateDecision.reject(step.prompt ?: "Awaiting human approval")
                                }
                            }
                        }

                        "shell", "local" -> {
                            shellStep(
                                stepId = step.id,
                                command = step.command,
                                timeoutSeconds = step.timeout,
                                retryCount = step.retryCount,
                                onError = step.onError,
                                maxOutputBytes = step.maxOutputBytes,
                                env = step.env,
                                workdir = config.workingDirectory,
                            )
                        }

                        "composite" -> {
                            val ref = step.workflowRef
                                ?: throw IllegalArgumentException(
                                    "Composite step '${step.id}' requires 'workflow_ref' field"
                                )

                            if (ref in activeChain) {
                                val path = (activeChain.toList() + ref).joinToString(" -> ")
                                throw IllegalStateException(
                                    "Circular composite step detected: workflow '$ref' is already " +
                                        "in the call chain. Chain: $path"
                                )
                            }

                            val subTemplate = registry.resolve(ref)
                            val subWorkflow = if (subTemplate.supportsRecursiveCompilation) {
                                subTemplate.compileRecursive(config, goal, registry, activeChain)
                            } else {
                                subTemplate.build(config, goal, "{}")
                            }

                            localStep(step.id) { state, _ ->
                                // Runs sub-workflow without observer/persistence/tracing
                                // (NoOp defaults). LLM provider calls still work via TramAI.
                                // NOTE: Uses NoOp observer by default. Wire SpolaWorkflowObserver for recursive observability.
                                val currentDepth = state.workflowNestingDepth
                                val maxDepth = 10
                                if (currentDepth >= maxDepth) {
                                    throw IllegalStateException(
                                        "Maximum composite nesting depth ($maxDepth) exceeded " +
                                            "in step '${step.id}'"
                                    )
                                }

                                val subResult = runBlocking {
                                    try {
                                        val timeoutMs = (step.timeout.coerceAtLeast(10) * 1000L)
                                        withTimeout(timeoutMs) {
                                            subWorkflow.run(
                                                initialState = state.copy(
                                                    workflowNestingDepth = currentDepth + 1,
                                                ),
                                                context = WorkflowContext(),
                                            )
                                        }
                                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                        throw IllegalStateException(
                                            "Composite step '${step.id}' timed out after ${step.timeout}s"
                                        )
                                    } catch (e: Exception) {
                                        throw IllegalStateException(
                                            "Composite step '${step.id}' failed: ${e.message}"
                                        )
                                    }
                                }
                                state.copy(
                                    result = subResult,
                                    intermediateResults = state.intermediateResults + (step.id to subResult),
                                )
                            }
                        }

                        else -> {
                            throw IllegalArgumentException(
                                "Unknown step type '${step.type}' in step '${step.id}'. " +
                                    "Supported types: ai, shell, local, parallel_agents, human_approval, composite"
                            )
                        }
                    }

                    // Insert done-condition gate after the step
                    if (step.done.isNotEmpty()) {
                        val heuristicConditions = step.done.filter { it.condition != "llm_judge" }
                        val llmConditions = step.done.filter { it.condition == "llm_judge" }
                        gateStep("${step.id}-done-check") { state, _ ->
                            // Evaluate heuristic conditions via DoneConditionEvaluator (non-suspend)
                            val heuristicPassed = if (heuristicConditions.isEmpty()) true
                            else DoneConditionEvaluator.evaluateAll(heuristicConditions, state, emptyMap())

                            // Evaluate LLM judge conditions via LLM (suspend)
                            val llmPassed = if (llmConditions.isEmpty() || llmJudgeProvider == null) true
                            else evaluateLlmConditions(llmConditions, state, llmJudgeProvider.first, llmJudgeProvider.second)

                            val passed = heuristicPassed && llmPassed
                            GateDecision(
                                allowed = passed,
                                reason = if (passed) null else "Done conditions not met for step '${step.id}'",
                            )
                        }
                    }
                }

                // After all steps, add global done-condition gate
                if (resolved.done.isNotEmpty()) {
                    val globalHeuristicConditions = resolved.done.filter { it.condition != "llm_judge" }
                    val globalLlmConditions = resolved.done.filter { it.condition == "llm_judge" }
                    gateStep("workflow-done-check") { state, _ ->
                        val heuristicPassed = if (globalHeuristicConditions.isEmpty()) true
                        else DoneConditionEvaluator.evaluateAll(globalHeuristicConditions, state, emptyMap())

                        val llmPassed = if (globalLlmConditions.isEmpty() || llmJudgeProvider == null) true
                        else evaluateLlmConditions(globalLlmConditions, state, llmJudgeProvider.first, llmJudgeProvider.second)

                        val passed = heuristicPassed && llmPassed
                        GateDecision(
                            allowed = passed,
                            reason = if (passed) null else "Global done conditions not met for workflow '${resolved.name}'",
                        )
                    }
                }
            },
            resultSelector = { state ->
                state.result ?: state.intermediateResults.values.joinToString("\n---\n")
            },
        )
    }

    internal suspend fun evaluateLlmConditions(
        conditions: List<DoneCondition>,
        state: SpolaState,
        provider: dev.tramai.core.provider.ModelProvider,
        model: String,
    ): Boolean {
        val output = state.result ?: return true
        for (condition in conditions) {
            val prompt = condition.value ?: "Is the output acceptable?"
            val request = ModelRequest(
                model = model,
                messages = listOf(
                    Message(MessageRole.SYSTEM, "You are an impartial judge. Evaluate if the output meets the criteria. Reply true/false."),
                    Message(MessageRole.USER, "Criteria: $prompt\n\nOutput:\n$output"),
                ),
            )
            val response = provider.complete(request)
            val verdict = response.content.trim().lowercase()
            if (!verdict.contains(Regex("\\btrue\\b"))) return false
        }
        return true
    }

    private fun WorkflowBuilder<SpolaState>.shellStep(
        stepId: String,
        command: String?,
        timeoutSeconds: Int,
        retryCount: Int,
        onError: OnError,
        maxOutputBytes: Long,
        env: Map<String, String>?,
        workdir: String,
    ) {
        localStep(stepId) { state, _ ->
            val resolvedCommand = command
                ?: throw IllegalArgumentException("Shell step '$stepId' has no command")
            val output = YamlWorkflowStepRunner.execute(
                command = resolvedCommand,
                state = state,
                timeoutSeconds = timeoutSeconds,
                retryCount = retryCount,
                onError = onError,
                maxOutputBytes = maxOutputBytes,
                env = env,
                workdir = workdir,
            )
            state.copy(
                result = output,
                intermediateResults = state.intermediateResults + (stepId to output),
            )
        }
    }
}
