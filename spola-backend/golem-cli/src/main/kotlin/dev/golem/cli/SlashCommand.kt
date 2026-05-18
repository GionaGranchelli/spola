package dev.spola.cli

/**
 * A single slash command in the Golem REPL.
 *
 * Each command has a [name], [description], and [usage] string.
 * The [execute] function returns `false` if the REPL should exit,
 * or `true` to continue.
 */
interface SlashCommand {
    val name: String
    val description: String
    val usage: String
    suspend fun execute(args: String, session: ReplSession): Boolean
}

// ── Command Registry ────────────────────────────────────────────────

/** All registered slash commands, indexed by name. Duplicate names silently overwrite (last wins). */
val SLASH_COMMANDS: Map<String, SlashCommand> = listOf(
    HelpCommand,
    ExitCommand,
    ClearCommand,
    ToolsCommand,
    MemoryCommand,
    PersonaCommand,
    HistoryCommand,
    ProvidersCommand,
    ModelsCommand,
    ModelCommand,
    ProviderCommand,
    SessionCommand,
    StatusCommand,
).associateBy { it.name }

// ── Implementations ─────────────────────────────────────────────────

object HelpCommand : SlashCommand {
    override val name = "help"
    override val description = "Show this help"
    override val usage = "/help"

    override suspend fun execute(args: String, session: ReplSession): Boolean {
        println("${ANSI_BOLD}Commands:${ANSI_RESET}")
        println()
        println("${ANSI_GREEN}Agent:${ANSI_RESET}")
        println("  ${ANSI_YELLOW}<goal>${ANSI_RESET}       Send a goal to the agent")
        println()
        println("${ANSI_GREEN}Session:${ANSI_RESET}")
        println("  ${ANSI_YELLOW}/history${ANSI_RESET}     Show current conversation history")
        println("  ${ANSI_YELLOW}/clear${ANSI_RESET}       Clear conversation")
        println("  ${ANSI_YELLOW}/session list|save|load <id>|delete <id>|new${ANSI_RESET} Manage sessions")
        println()
        println("${ANSI_GREEN}Info:${ANSI_RESET}")
        println("  ${ANSI_YELLOW}/help${ANSI_RESET}        Show this help")
        println("  ${ANSI_YELLOW}/tools${ANSI_RESET}       List available tools")
        println("  ${ANSI_YELLOW}/memory${ANSI_RESET}      Show stored memory entries")
        println("  ${ANSI_YELLOW}/persona${ANSI_RESET}     Show the current persona")
        println("  ${ANSI_YELLOW}/status${ANSI_RESET}      Show current provider, model, workdir")
        println("  ${ANSI_YELLOW}/providers${ANSI_RESET}   List available providers")
        println("  ${ANSI_YELLOW}/models${ANSI_RESET}      List known models for current provider")
        println()
        println("${ANSI_GREEN}Settings:${ANSI_RESET}")
        println("  ${ANSI_YELLOW}/model <name>${ANSI_RESET}    Switch model")
        println("  ${ANSI_YELLOW}/provider <name>${ANSI_RESET} Switch provider")
        println()
        println("${ANSI_GREEN}Exit:${ANSI_RESET}")
        println("  ${ANSI_YELLOW}/exit${ANSI_RESET}, ${ANSI_YELLOW}/quit${ANSI_RESET} Exit the REPL")
        return true
    }
}

object ExitCommand : SlashCommand {
    override val name = "exit"
    override val description = "Exit the REPL"
    override val usage = "/exit or /quit"

    override suspend fun execute(args: String, session: ReplSession): Boolean {
        println("${ANSI_GREEN}Goodbye!${ANSI_RESET}")
        return false
    }
}

object ClearCommand : SlashCommand {
    override val name = "clear"
    override val description = "Clear conversation"
    override val usage = "/clear"

    override suspend fun execute(args: String, session: ReplSession): Boolean {
        session.clear()
        println("${ANSI_GREEN}Conversation cleared.${ANSI_RESET}")
        return true
    }
}

object ToolsCommand : SlashCommand {
    override val name = "tools"
    override val description = "List available tools"
    override val usage = "/tools"

    override suspend fun execute(args: String, session: ReplSession): Boolean {
        val toolRegistry = session.instance.toolRegistry
        println("${ANSI_BOLD}Available tools:${ANSI_RESET}")
        for (tool in toolRegistry.list()) {
            val params = tool.parameters.joinToString(", ") { p ->
                val opt = if (!p.required) " (optional)" else ""
                "${p.name}: ${p.type.name.lowercase()}$opt"
            }
            println("  ${ANSI_CYAN}${tool.name}${ANSI_RESET}($params)")
            println("    ${ANSI_DIM}${tool.description}${ANSI_RESET}")
        }
        return true
    }
}

object MemoryCommand : SlashCommand {
    override val name = "memory"
    override val description = "Show stored memory entries"
    override val usage = "/memory"

    override suspend fun execute(args: String, session: ReplSession): Boolean {
        val memoryStore = session.instance.memoryStore
        val entries = memoryStore.listAll()
        if (entries.isEmpty()) {
            println("${ANSI_DIM}No memory entries.${ANSI_RESET}")
            return true
        }
        println("${ANSI_BOLD}Memory entries (${entries.size}):${ANSI_RESET}")
        for (entry in entries) {
            println("  ${ANSI_CYAN}[${entry.key}]${ANSI_RESET}")
            println("    ${ANSI_DIM}${entry.value}${ANSI_RESET}")
        }
        return true
    }
}

object PersonaCommand : SlashCommand {
    override val name = "persona"
    override val description = "Show the current persona"
    override val usage = "/persona"

    override suspend fun execute(args: String, session: ReplSession): Boolean {
        println("${ANSI_DIM}${session.instance.persona}${ANSI_RESET}")
        return true
    }
}

object HistoryCommand : SlashCommand {
    override val name = "history"
    override val description = "Show current conversation history"
    override val usage = "/history"

    override suspend fun execute(args: String, session: ReplSession): Boolean {
        val conv = session.getConversation()
        if (conv.isEmpty()) {
            println("${ANSI_DIM}No conversation history.${ANSI_RESET}")
            return true
        }
        println("${ANSI_BOLD}Conversation history (${conv.size} messages):${ANSI_RESET}")
        for (msg in conv) {
            val roleTag = when (msg.role) {
                "system" -> "${ANSI_YELLOW}system${ANSI_RESET}"
                "user" -> "${ANSI_GREEN}user${ANSI_RESET}"
                "assistant" -> "${ANSI_CYAN}assistant${ANSI_RESET}"
                "tool" -> "${ANSI_DIM}tool${ANSI_RESET}"
                else -> msg.role
            }
            val preview = msg.content.replace("\n", "\\n").take(200)
            println("  [$roleTag] ${ANSI_DIM}$preview${ANSI_RESET}")
        }
        return true
    }
}

object StatusCommand : SlashCommand {
    override val name = "status"
    override val description = "Show current provider, model, workdir"
    override val usage = "/status"

    override suspend fun execute(args: String, session: ReplSession): Boolean {
        val cfg = session.instance.config
        println("${ANSI_BOLD}Status:${ANSI_RESET}")
        println("  ${ANSI_CYAN}Provider:${ANSI_RESET}  ${cfg.provider}")
        println("  ${ANSI_CYAN}Model:${ANSI_RESET}     ${cfg.model}")
        println("  ${ANSI_CYAN}Verbosity:${ANSI_RESET} ${cfg.verbosity}")
        println("  ${ANSI_CYAN}Workdir:${ANSI_RESET}   ${cfg.workingDirectory}")
        println("  ${ANSI_CYAN}Turns:${ANSI_RESET}     ${session.turnNumber}")
        println("  ${ANSI_CYAN}Messages:${ANSI_RESET}  ${session.getConversation().size}")
        return true
    }
}

object ProvidersCommand : SlashCommand {
    override val name = "providers"
    override val description = "List available providers"
    override val usage = "/providers"

    override suspend fun execute(args: String, session: ReplSession): Boolean {
        println("${ANSI_BOLD}Available providers:${ANSI_RESET}")
        println("  ${ANSI_CYAN}openai${ANSI_RESET}         — Requires OPENAI_API_KEY")
        println("  ${ANSI_CYAN}anthropic${ANSI_RESET}      — Requires ANTHROPIC_API_KEY")
        println("  ${ANSI_CYAN}openai-compat${ANSI_RESET}  — Uses OPENAI_API_KEY or OPENAI_COMPAT_API_KEY")
        println("  ${ANSI_CYAN}ollama${ANSI_RESET}         — Local, no API key needed")
        println("  ${ANSI_CYAN}google${ANSI_RESET}         — Requires GOOGLE_API_KEY")
        return true
    }
}

object ModelsCommand : SlashCommand {
    override val name = "models"
    override val description = "List known models for current provider"
    override val usage = "/models"

    override suspend fun execute(args: String, session: ReplSession): Boolean {
        val cfg = session.instance.config
        println("${ANSI_BOLD}Known models for provider '${cfg.provider}':${ANSI_RESET}")
        println("  This is an advisory list. Unknown model names may still work.")
        println()
        when (cfg.provider) {
            "openai" -> println("  gpt-4o, gpt-4o-mini, gpt-4-turbo, gpt-3.5-turbo")
            "anthropic" -> println("  claude-sonnet-4-20250514, claude-3-opus-latest, claude-3-haiku-20240307")
            "google" -> println("  gemini-2.5-pro, gemini-2.5-flash, gemini-2.0-flash")
            "ollama" -> println("  llama3, llama2, mistral, codellama, mixtral, ...")
            "openai-compat" -> println("  Varies by endpoint. Check your compatible provider's docs.")
            else -> println("  No model list available for '${cfg.provider}'.")
        }
        println()
        println("  Use ${ANSI_YELLOW}/model <name>${ANSI_RESET} to switch.")
        return true
    }
}

object ModelCommand : SlashCommand {
    override val name = "model"
    override val description = "Switch model"
    override val usage = "/model <name>"

    override suspend fun execute(args: String, session: ReplSession): Boolean {
        val modelName = args.trim()
        if (modelName.isBlank()) {
            println("${ANSI_YELLOW}Usage: /model <name>${ANSI_RESET}")
            return true
        }
        try {
            session.instance.reconfigure(session.instance.config.provider, modelName)
            println("${ANSI_GREEN}Model set to: ${ANSI_BOLD}$modelName${ANSI_RESET}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("${ANSI_RED}Failed to switch model: ${e.message ?: e.javaClass.simpleName}${ANSI_RESET}")
        }
        return true
    }
}

object ProviderCommand : SlashCommand {
    override val name = "provider"
    override val description = "Switch provider"
    override val usage = "/provider <name>"

    override suspend fun execute(args: String, session: ReplSession): Boolean {
        val providerName = args.trim()
        if (providerName.isBlank()) {
            println("${ANSI_YELLOW}Usage: /provider <name>${ANSI_RESET}")
            return true
        }
        try {
            session.instance.reconfigure(providerName, session.instance.config.model)
            println("${ANSI_GREEN}Provider set to: ${ANSI_BOLD}$providerName${ANSI_RESET}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("${ANSI_RED}Failed to switch provider: ${e.message ?: e.javaClass.simpleName}${ANSI_RESET}")
        }
        return true
    }
}

object SessionCommand : SlashCommand {
    override val name = "session"
    override val description = "Manage sessions: list, save, load, delete, new"
    override val usage = "/session list|save|load <id>|delete <id>|new"

    override suspend fun execute(args: String, session: ReplSession): Boolean {
        val parts = args.trim().split("\\s+".toRegex(), 2)
        val subcommand = parts.getOrElse(0) { "" }
        val subArgs = parts.getOrElse(1) { "" }

        when (subcommand) {
            "list" -> {
                val sessions = session.listSessions()
                if (sessions.isEmpty()) {
                    println("${ANSI_DIM}No saved sessions.${ANSI_RESET}")
                    return true
                }
                println("${ANSI_BOLD}Saved sessions (${sessions.size} checkpoints):${ANSI_RESET}")
                val grouped = sessions.groupBy { it.sessionId }
                for ((sid, checkpoints) in grouped) {
                    val latest = checkpoints.maxByOrNull { it.turnNumber } ?: continue
                    val label = if (sid == session.getSessionId()) " ${ANSI_GREEN}(current)${ANSI_RESET}" else ""
                    println("  ${ANSI_CYAN}$sid${ANSI_RESET}$label")
                    println("    Turns: ${latest.turnNumber}  |  Created: ${latest.createdAt}")
                }
            }
            "save" -> {
                val result = session.save()
                if (result != null) {
                    println("${ANSI_GREEN}Session saved. ID: ${ANSI_BOLD}${session.getSessionId()}${ANSI_RESET}")
                } else {
                    println("${ANSI_YELLOW}Checkpoint manager not available.${ANSI_RESET}")
                }
            }
            "load" -> {
                if (subArgs.isBlank()) {
                    println("${ANSI_YELLOW}Usage: /session load <id>${ANSI_RESET}")
                    return true
                }
                val success = session.load(subArgs)
                if (success) {
                    println("${ANSI_GREEN}Session loaded: ${ANSI_BOLD}$subArgs${ANSI_RESET}")
                } else {
                    println("${ANSI_YELLOW}Session not found: $subArgs${ANSI_RESET}")
                }
            }
            "delete" -> {
                if (subArgs.isBlank()) {
                    println("${ANSI_YELLOW}Usage: /session delete <id>${ANSI_RESET}")
                    return true
                }
                val deleted = session.deleteSession(subArgs)
                if (deleted) {
                    println("${ANSI_GREEN}Session deleted: $subArgs${ANSI_RESET}")
                } else {
                    println("${ANSI_YELLOW}Session not found: $subArgs${ANSI_RESET}")
                }
            }
            "new" -> {
                session.newSession()
                println("${ANSI_GREEN}New session started.${ANSI_RESET}")
            }
            else -> {
                println("${ANSI_YELLOW}Usage: /session list|save|load <id>|delete <id>|new${ANSI_RESET}")
            }
        }
        return true
    }
}

// ── ANSI constants (mirrored for CLI independence from core) ────────

internal const val ANSI_CYAN   = "\u001B[36m"
internal const val ANSI_RED    = "\u001B[31m"
internal const val ANSI_GREEN  = "\u001B[32m"
internal const val ANSI_YELLOW = "\u001B[33m"
internal const val ANSI_BOLD   = "\u001B[1m"
internal const val ANSI_DIM    = "\u001B[2m"
internal const val ANSI_RESET  = "\u001B[0m"
