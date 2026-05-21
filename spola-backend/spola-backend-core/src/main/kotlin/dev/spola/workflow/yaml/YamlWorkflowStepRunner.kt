package dev.spola.workflow.yaml

import dev.spola.workflow.SpolaState
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Executes shell/local workflow steps via ProcessBuilder.
 */
object YamlWorkflowStepRunner {
    private val logger = LoggerFactory.getLogger(YamlWorkflowStepRunner::class.java)
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "spola-shell-io").apply {
            isDaemon = true
        }
    }

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
    ): ShellStepResult {
        if (command.isBlank()) {
            throw IllegalArgumentException("Command is empty")
        }

        val pb = ProcessBuilder("/bin/sh", "-c", command)
            .redirectErrorStream(false)
        if (env != null) {
            pb.environment().putAll(env)
        }
        val process = pb.start()
        val outputLimit = maxOutputBytes.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

        val stdoutFuture = CompletableFuture.supplyAsync({
            try {
                val bytes = process.inputStream.readNBytes(outputLimit)
                String(bytes, Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }, ioExecutor)
        val stderrFuture = CompletableFuture.supplyAsync({
            try {
                val bytes = process.errorStream.readNBytes(outputLimit)
                String(bytes, Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }, ioExecutor)

        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            stdoutFuture.cancel(true)
            stderrFuture.cancel(true)
            throw IllegalStateException("Command timed out after ${timeoutSeconds}s: $command")
        }

        val stdout = stdoutFuture.join()
        val stderr = stderrFuture.join()
        return ShellStepResult(
            stdout = stdout,
            stderr = stderr,
            exitCode = process.exitValue(),
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
