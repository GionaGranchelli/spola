package dev.spola.workflow.yaml

import dev.spola.agent.PermissionEnforcer
import dev.spola.tools.executeShellCommand
import dev.spola.tools.toolResultToShellExecutionResult
import dev.spola.workflow.SpolaState
import org.slf4j.LoggerFactory

/**
 * Executes shell/local workflow steps through the shared shell execution path.
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
