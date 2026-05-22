package dev.spola.workflow.yaml

import dev.spola.agent.PermissionEnforcer
import dev.spola.tools.executeShellCommand
import dev.spola.tools.toolResultToShellExecutionResult
import dev.spola.workflow.SpolaState
import org.slf4j.LoggerFactory

// ========================================================================
// STEP RUNNER MAPPING: YAML Step Type → run() Behaviour → DSL Equivalent
// ========================================================================
//
// This runner is called exclusively by `shell` / `local` step types from
// the YAML compiler. Below is the complete mapping of what each YAML step
// type does at runtime vs. how the equivalent TramAI DSL step works.
//
// ─────────────────────────────────────────────────────────────────────────
// YAML step type: shell / local
// ─────────────────────────────────────────────────────────────────────────
//   Used in YAML workflow:
//     - id: build
//       type: shell
//       command: "./gradlew build"
//       timeout: 120
//       retry_count: 2
//       on_error: FAIL
//       max_output_bytes: 10485760
//
//   Runtime behaviour (YamlWorkflowStepRunner.execute()):
//     • Resolves {{state.X}} templates in the command string
//     • Resolves {{state.X}} templates in env values
//     • Executes via ProcessBuilder through Spola's shared executeShellCommand()
//     • Respects retry_count — retries on non-zero exit or exceptions
//     • Handles on_error=CONTINUE by returning "[ERROR] ..." string instead of throwing
//     • Clamps max_output_bytes and timeout_seconds
//     • Delegates to executeOnce() → executeShellCommand() → PermissionEnforcer
//
//   Equivalent TramAI DSL (WorkflowBuilder.shellStep):
//     shellStep<SpolaState>(
//         name = "build",
//         config = ShellStepConfig(
//             timeoutSeconds = 120,
//             maxOutputBytes = 10 * 1024 * 1024,
//             failOnNonZeroExit = true,   // ← on_error: FAIL vs CONTINUE
//         ),
//         definition = ShellCommandDefinition(
//             executable = "bash",
//             hasWorkdir = true,
//             envKeys = setOf("PATH", "HOME"),  // declared env vars
//         ),
//         command = { state, _ ->
//             ShellCommand(
//                 command = "./gradlew build",
//                 workdir = state.config.workingDirectory,
//                 env = mapOf("MY_VAR" to "value"),
//             )
//         },
//         merge = { state, result, _ ->
//             state.copy(
//                 result = result.stdout,
//                 intermediateResults = state.intermediateResults + ("build" to result.stdout),
//             )
//         },
//     )
//
//   Key differences:
//     1. The YAML compiler wraps this in a plain localStep(), not shellStep().
//        This means the YAML path does NOT use ShellWorkflowStep, ShellStepConfig,
//        or the TramAI security policy framework (allowedCommands, deniedCommands).
//     2. The YAML path implements its own retry loop inline in execute().
//     3. The YAML path uses a single-string command rather than a structured
//        ShellCommand type.
//     4. Template resolution ({{param.X}} / {{state.X}}) is handled at compile
//        time via WorkflowParameterResolver; only {{state.X}} is resolved at runtime.
//
// ─────────────────────────────────────────────────────────────────────────
// YAML step type: ai
// ─────────────────────────────────────────────────────────────────────────
//   This runner is NOT invoked for `ai` steps. The `ai` type is compiled
//   directly into a spolaAgentStep() call (which wraps TramAI's aiStep).
//   Execution flows through WorkflowSteps.runSpolaAgent():
//     → SpolaFactory.create(config)
//     → instance.agent.run(persona, goal, observer)
//
//   Equivalent TramAI DSL:
//     aiStep(name = "analyze") {
//         input = { state -> SpolaAgentStepInput(...) }
//         invoke = { input -> WorkflowSteps.runSpolaAgent(input) }
//         merge = { state, result -> state.copy(result = result) }
//     }
//
// ─────────────────────────────────────────────────────────────────────────
// YAML step type: parallel_agents
// ─────────────────────────────────────────────────────────────────────────
//   This runner is NOT invoked for `parallel_agents` steps. The type is
//   compiled into a parallelAgentsStep() call (which wraps TramAI's
//   parallelStep). Execution flows through:
//     → SqliteAgentStore.get(agentId) for each agent
//     → SpolaFactory.createFromAgentDefinition(agentDef, config)
//     → instance.agent.run(systemPrompt, goal, observer) in parallel
//
//   Equivalent TramAI DSL:
//     parallelStep(name = "reviewers") {
//         items = { state -> agents.map { it to goal(state) } }
//         invoke = { (agentId, agentGoal) -> runAgent(agentId, agentGoal) }
//         merge = { state, results -> state.copy(intermediateResults = ...) }
//     }
//
// ─────────────────────────────────────────────────────────────────────────
// YAML step type: human_approval
// ─────────────────────────────────────────────────────────────────────────
//   This runner is NOT invoked. The type is compiled into a gateStep()
//   that checks state.intermediateResults["__approval_granted"].
//
//   Equivalent TramAI DSL:
//     gateStep("approve") { state, _ ->
//         if (state.intermediateResults["__approval_granted"] == "true")
//             GateDecision.allow()
//         else
//             GateDecision.reject("Awaiting human approval")
//     }
//
// ─────────────────────────────────────────────────────────────────────────
// YAML step type: composite
// ─────────────────────────────────────────────────────────────────────────
//   This runner is NOT invoked. The type is compiled into a localStep()
//   that runs a sub-workflow:
//     → subWorkflow.run(initialState, WorkflowContext())
//     → result stored in state.result + intermediateResults
//
//   Equivalent TramAI DSL:
//     localStep("sub-workflow") { state, _ ->
//         val subResult = subWorkflow.run(state, WorkflowContext())
//         state.copy(result = subResult, ...)
//     }
//
// ========================================================================

/**
 * Executes shell/local workflow steps through the shared shell execution path.
 *
 * ## Role in YAML → DSL mapping
 *
 * This runner is only used by `shell` / `local` YAML step types.
 * See the mapping block comment at the top of this file for the complete
 * YAML → TramAI DSL translation table covering all step types.
 *
 * For the DSL equivalent of this execution path, see:
 *   WorkflowBuilder.shellStep() — TramAI's native shell step with
 *   structured ShellCommandDefinition and ShellStepConfig.
 */
object YamlWorkflowStepRunner {
    private val logger = LoggerFactory.getLogger(YamlWorkflowStepRunner::class.java)

    data class ShellStepResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    )

    /**
     * Execute a shell command via ProcessBuilder.
     * Resolves templates, enforces timeout, handles retries and onError.
     */
    @Deprecated("Use TramAI workflow DSL shellStep instead")
    fun execute(
        command: String,
        state: SpolaState,
        timeoutSeconds: Int = 60,
        retryCount: Int = 0,
        onError: OnError = OnError.FAIL,
        maxOutputBytes: Long = 10 * 1024 * 1024,
        env: Map<String, String>? = null,
        workdir: String = ".",
        permissionEnforcer: PermissionEnforcer? = null,
    ): String {
        val resolvedCommand = WorkflowParameterResolver.resolveRuntimeTemplates(
            command,
            state.intermediateResults,
        ).trim()
        val resolvedEnv = env?.mapValues { (_, value) ->
            WorkflowParameterResolver.resolveRuntimeTemplates(value, state.intermediateResults)
        }
        val maxAttempts = retryCount.coerceAtLeast(0) + 1
        var lastError: String? = null

        repeat(maxAttempts) { attempt ->
            try {
                val result = executeOnce(
                    command = resolvedCommand,
                    timeoutSeconds = timeoutSeconds.coerceAtLeast(1),
                    maxOutputBytes = maxOutputBytes,
                    env = resolvedEnv,
                    workdir = workdir,
                    permissionEnforcer = permissionEnforcer,
                )

                if (result.exitCode == 0) {
                    return result.stdout
                }

                val errorMessage = buildFailureMessage(
                    command = resolvedCommand,
                    stderr = result.stderr,
                    stdout = result.stdout,
                    exitCode = result.exitCode,
                )
                lastError = errorMessage
                logger.warn(
                    "Shell/local step attempt {}/{} failed with exit code {}: {}",
                    attempt + 1,
                    maxAttempts,
                    result.exitCode,
                    resolvedCommand,
                )
            } catch (e: Exception) {
                lastError = e.message ?: "Shell/local step failed"
                logger.warn(
                    "Shell/local step attempt {}/{} failed for command '{}': {}",
                    attempt + 1,
                    maxAttempts,
                    resolvedCommand,
                    lastError,
                )
            }
        }

        val failure = lastError ?: "Shell/local step failed: $resolvedCommand"
        if (onError == OnError.CONTINUE) {
            return "[ERROR] $failure"
        }
        throw IllegalStateException(failure)
    }

    private fun executeOnce(
        command: String,
        timeoutSeconds: Int,
        maxOutputBytes: Long,
        env: Map<String, String>?,
        workdir: String,
        permissionEnforcer: PermissionEnforcer?,
    ): ShellStepResult {
        val result = executeShellCommand(
            commandStr = command,
            workdirStr = workdir,
            timeoutSec = timeoutSeconds,
            permissionEnforcer = permissionEnforcer,
            env = env,
            maxOutputSize = maxOutputBytes.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            useShell = true,
        )
        val shellResult = toolResultToShellExecutionResult(result)
        return ShellStepResult(
            stdout = shellResult.stdout,
            stderr = shellResult.stderr,
            exitCode = shellResult.exitCode,
        )
    }

    private fun buildFailureMessage(
        command: String,
        stderr: String,
        stdout: String,
        exitCode: Int,
    ): String {
        val stderrText = stderr.trim()
        val stdoutText = stdout.trim()
        return when {
            stderrText.isNotEmpty() -> "Command failed (exit $exitCode): $stderrText"
            stdoutText.isNotEmpty() -> "Command failed (exit $exitCode): $stdoutText"
            else -> "Command failed with exit code $exitCode: $command"
        }
    }

}
