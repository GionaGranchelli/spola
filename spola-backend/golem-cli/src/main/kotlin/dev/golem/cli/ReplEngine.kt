package dev.spola.cli

import dev.spola.*
import dev.spola.Verbosity.DEBUG
import dev.spola.Verbosity.NORMAL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.TerminalBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * A REPL session owns the conversation transcript and the agent instance.
 *
 * Unlike [GolemAgent.run], which clears and rebuilds the conversation on
 * every call, [ReplSession.runGoal] appends the new goal to the external
 * transcript and mutates it in-place through the entire ReAct loop.
 * This preserves all intermediate messages (tool calls, results, assistant
 * replies) for /history, /clear, /retry, and session persistence.
 */
class ReplSession(
    /** The active Golem agent instance. Mutable for /model and /provider switches. */
    var instance: GolemInstance,
) {
    /** External conversation transcript, mutated in-place by runFull(). */
    private val transcript = mutableListOf<ChatMessage>()

    /** Current session ID for checkpoint save/load. Null means new unsaved session. */
    private var sessionId: String? = null

    /** Number of goals executed in this session. */
    var turnNumber: Int = 0
        private set

    /** Run a single goal, mutating the session transcript in-place. */
    suspend fun runGoal(goal: String): String {
        turnNumber++
        return instance.agent.runFull(
            persona = instance.persona,
            goal = goal,
            transcript = transcript,
            observer = instance.observer,
        )
    }

    /** Clear the conversation transcript and reset turn counter. */
    fun clear() {
        transcript.clear()
        turnNumber = 0
    }

    /** Get current session ID. */
    fun getSessionId(): String? = sessionId

    /** Save current conversation to checkpoints. Returns checkpoint ID or null. */
    suspend fun save(): Long? {
        val cm = instance.agent.getCheckpointManager() ?: return null
        val sid = sessionId ?: java.util.UUID.randomUUID().toString().take(16).also { sessionId = it }
        return cm.save(sid, turnNumber, transcript.toList())
    }

    /** Load a saved session by its session ID. Returns false if session not found. */
    suspend fun load(sid: String): Boolean {
        val cm = instance.agent.getCheckpointManager() ?: return false
        val conversation = cm.loadConversation(sid) ?: return false
        // Safe: called only from REPL loop which processes commands sequentially.
        // transcript is never modified concurrently by runGoal during a /session load.
        transcript.clear()
        transcript.addAll(conversation)
        sessionId = sid
        val lastTurn = cm.listForSession(sid).maxOfOrNull { it.turnNumber } ?: 0
        turnNumber = lastTurn
        return true
    }

    /** Delete all checkpoints for a session ID. Returns true if any were deleted. */
    fun deleteSession(sid: String): Boolean {
        val cm = instance.agent.getCheckpointManager() ?: return false
        return cm.deleteForSession(sid) > 0
    }

    /** List all saved sessions (deduplicated by session ID with latest info). */
    fun listSessions(): List<dev.spola.checkpoint.CheckpointData> {
        val cm = instance.agent.getCheckpointManager() ?: return emptyList()
        return cm.list()
    }

    /** Start a new session (clear transcript, reset session ID). */
    fun newSession() {
        clear()
        sessionId = null
    }

    /** Return an immutable snapshot of the conversation. */
    fun getConversation(): List<ChatMessage> = transcript.toList()

    /**
     * Replace the underlying GolemInstance (for /model or /provider switch).
     * The conversation transcript survives the switch.
     */
    fun replaceInstance(newInstance: GolemInstance) {
        instance.close()
        instance = newInstance
    }

    /** Close the underlying GolemInstance and release resources. */
    fun close() {
        instance.close()
    }
}

/**
 * Run Golem in REPL mode: interactive loop reading goals, dispatching slash
 * commands, and showing results.
 */
suspend fun runRepl(config: GolemConfig = GolemConfig()) {
    var currentConfig = config
    val observer = ConsoleObserver(config.verbosity)
    var session = ReplSession(
        instance = GolemFactory.create(config = currentConfig, observer = observer),
    )

    println("${ANSI_BOLD}Golem v${GolemVersion.VERSION}${ANSI_RESET} ${ANSI_DIM}— JVM Autonomous Coding Agent${ANSI_RESET}")
    println("Type your goal, or ${ANSI_YELLOW}/help${ANSI_RESET} for commands.")
    println()

    // Gradle's `run` task doesn't connect stdin for interactive apps.
    val console = System.console()
    if (console == null) {
        println("${ANSI_YELLOW}⚠ No interactive terminal detected.${ANSI_RESET}")
        println("   The Gradle run task does not support interactive REPL.")
        println()
        println("   Instead, build a distribution and run it directly:")
        println("     ${ANSI_CYAN}./gradlew :golem-cli:installDist${ANSI_RESET}")
        println("     ${ANSI_CYAN}./golem-cli/build/install/golem-cli/bin/golem-cli${ANSI_RESET}")
        println()
        println("   Or use one-shot mode:")
        println("     ${ANSI_CYAN}./gradlew :golem-cli:run --args=\\\"'your goal here'\\\"${ANSI_RESET}")
        println()
        println("   Or run with -Dorg.gradle.internal.interactive=true (experimental):")
        println("     ${ANSI_CYAN}./gradlew :golem-cli:run --console=plain${ANSI_RESET}")
        session.close()
        return
    }

    try {
        try {
            val historyFile = Path.of(System.getProperty("user.home"), ".golem", "repl-history.txt")
            Files.createDirectories(historyFile.parent)

            val terminal = TerminalBuilder.builder()
                .system(true)
                .build()

            try {
                val reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(JLineCompleter())
                    .variable(LineReader.HISTORY_FILE, historyFile)
                    .option(LineReader.Option.BRACKETED_PASTE, true)
                    .build()

                while (true) {
                    val line = try {
                        reader.readLine("> ")
                    } catch (_: UserInterruptException) {
                        println()
                        continue
                    } catch (_: EndOfFileException) {
                        break
                    }
                    val trimmed = line.trim()

                    when {
                        trimmed.isEmpty() -> continue

                        // /quit is an alias for /exit
                        trimmed == "/exit" || trimmed == "/quit" -> {
                            if (!ExitCommand.execute("", session)) break
                        }

                        trimmed.startsWith("/") -> {
                            // Parse /cmd [args]
                            val spaceIdx = trimmed.indexOf(' ')
                            val cmdName = if (spaceIdx > 0) trimmed.substring(1, spaceIdx).lowercase()
                                          else trimmed.removePrefix("/").lowercase()
                            val cmdArgs = if (spaceIdx > 0) trimmed.substring(spaceIdx + 1) else ""

                            val command = SLASH_COMMANDS[cmdName]
                            if (command != null) {
                                val shouldContinue = command.execute(cmdArgs, session)
                                if (!shouldContinue) break
                            } else {
                                // Unknown command — send to agent as goal
                                runGoal(session, trimmed)
                            }
                        }

                        else -> {
                            runGoal(session, trimmed)
                        }
                    }
                }
            } finally {
                terminal.close()
            }
        } catch (_: Exception) {
            while (true) {
                print("${ANSI_YELLOW}> ${ANSI_RESET}")
                System.out.flush()
                val line = console.readLine() ?: break
                val trimmed = line.trim()

                when {
                    trimmed.isEmpty() -> continue

                    // /quit is an alias for /exit
                    trimmed == "/exit" || trimmed == "/quit" -> {
                        if (!ExitCommand.execute("", session)) break
                    }

                    trimmed.startsWith("/") -> {
                        // Parse /cmd [args]
                        val spaceIdx = trimmed.indexOf(' ')
                        val cmdName = if (spaceIdx > 0) trimmed.substring(1, spaceIdx).lowercase()
                                      else trimmed.removePrefix("/").lowercase()
                        val cmdArgs = if (spaceIdx > 0) trimmed.substring(spaceIdx + 1) else ""

                        val command = SLASH_COMMANDS[cmdName]
                        if (command != null) {
                            val shouldContinue = command.execute(cmdArgs, session)
                            if (!shouldContinue) break
                        } else {
                            // Unknown command — send to agent as goal
                            runGoal(session, trimmed)
                        }
                    }

                    else -> {
                        runGoal(session, trimmed)
                    }
                }
            }
        }
    } finally {
        session.close()
    }
}

private suspend fun runGoal(session: ReplSession, goal: String) {
    coroutineScope {
        val startTime = System.nanoTime()
        val showSpinner = session.instance.config.verbosity == NORMAL
        val spinner = if (showSpinner) {
            launch {
                val frames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
                var i = 0
                while (isActive) {
                    print("\r${ANSI_DIM}${ANSI_YELLOW}${frames[i]} Processing...${ANSI_RESET}")
                    System.out.flush()
                    i = (i + 1) % frames.size
                    delay(200)
                }
            }
        } else {
            null
        }
        try {
            val result = session.runGoal(goal)
            if (session.instance.config.verbosity == NORMAL) {
                println("${ANSI_CYAN}$result${ANSI_RESET}")
            } else {
                // Tokens already streamed via onToken; show a dimmed summary marker
                println("${ANSI_DIM}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${ANSI_RESET}")
            }
            val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
            println("${ANSI_DIM}Completed in ${String.format("%.1f", elapsed)}s${ANSI_RESET}")
        } catch (e: Exception) {
            println("${ANSI_RED}Error: ${e.message}${ANSI_RESET}")
        } finally {
            spinner?.cancel()
            spinner?.join()
            if (showSpinner) {
                print("\r\u001B[2K") // clear the spinner line
                System.out.flush()
            }
        }
    }
}

/**
 * Observer that prints tool call events to the terminal in real-time.
 */
class ConsoleObserver(
    private val verbosity: Verbosity = NORMAL,
) : AgentRunObserver {
    override suspend fun onStatus(status: String, message: String?) {
        if (verbosity == NORMAL) return
        if (verbosity != DEBUG && status in DEBUG_ONLY_STATUSES) return
        println("${ANSI_DIM}• $status${message?.let { ": $it" } ?: ""}${ANSI_RESET}")
    }

    override suspend fun onToken(text: String) {
        if (verbosity == NORMAL) return
        print("${ANSI_CYAN}$text${ANSI_RESET}")
        System.out.flush()
    }

    override suspend fun onToolCall(toolCall: ToolCall) {
        val args = toolCall.arguments.entries.joinToString(", ") { (k, v) ->
            val value = v.toString()
            val rendered = if (verbosity == NORMAL) value.take(80) else value
            "$k=$rendered"
        }
        println("${ANSI_YELLOW}🔧 ${toolCall.name} → running...${ANSI_RESET}")
        if (args.isNotEmpty()) {
            println("${ANSI_DIM}   args: $args${ANSI_RESET}")
        }
        if (verbosity == DEBUG && toolCall.name.startsWith("memory_")) {
            println("${ANSI_DIM}   memory op: ${toolCall.name}${ANSI_RESET}")
        }
    }

    override suspend fun onToolResult(toolCall: ToolCall, result: ToolResult) {
        if (result.success) {
            println("${ANSI_GREEN}🔧 ${toolCall.name} ✓${ANSI_RESET}")
        } else {
            println("${ANSI_RED}🔧 ${toolCall.name} ✗ ${result.error ?: result.output}${ANSI_RESET}")
        }
        if (verbosity == DEBUG) {
            val body = result.output.ifBlank { result.error ?: "" }
            if (body.isNotBlank()) {
                println("${ANSI_DIM}   output: $body${ANSI_RESET}")
            }
        }
    }

    override suspend fun onLlmCall(model: String, provider: String) {
        println("${ANSI_DIM}🤖 LLM call: $provider/$model${ANSI_RESET}")
    }

    override suspend fun onLlmResult(model: String, provider: String, inputTokens: Int?, outputTokens: Int?) {
        val tokenInfo = buildString {
            append("${ANSI_DIM}✅ LLM result: $provider/$model")
            if (inputTokens != null || outputTokens != null) {
                val inp = inputTokens?.toString() ?: "?"
                val out = outputTokens?.toString() ?: "?"
                append(" (in: $inp, out: $out)")
            }
            append("${ANSI_RESET}")
        }
        println(tokenInfo)
    }

    override suspend fun onError(error: Throwable) {
        println("${ANSI_RED}✗ Error: ${error.message}${ANSI_RESET}")
    }

    private companion object {
        val DEBUG_ONLY_STATUSES = setOf("llm_request")
    }
}
