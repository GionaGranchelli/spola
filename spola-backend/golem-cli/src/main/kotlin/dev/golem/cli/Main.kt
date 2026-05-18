package dev.spola.cli

import dev.spola.GolemConfig
import dev.spola.Verbosity
import dev.spola.api.GolemApiServer
import dev.spola.agent.SqliteAgentStore
import dev.spola.config.GolemConfigFileStore
import dev.spola.mcp.runMcpServer
import dev.spola.runOneShot
import dev.spola.scheduler.GolemJobStore
import dev.spola.scheduler.GolemScheduler
import dev.spola.scheduler.SqliteGolemJobStore
import dev.spola.workflow.AsyncWorkflowDispatcher
import dev.spola.workflow.SqliteWorkflowExecutionStore
import dev.spola.workflow.WorkflowExecutionService
import dev.spola.workflow.WorkflowSchedulerService
import dev.spola.workflow.WorkflowTemplateRegistry
import dev.spola.workflow.registerBuiltInTemplates
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

@Serializable
data class PairingCliInfo(
    val host: String,
    val port: Int,
    val token: String,
    val trustId: String,
    val version: String,
)

@Command(
    name = "golem",
    version = ["0.1.0"],
    description = ["Golem \u2014 JVM Autonomous Coding Agent"],
    mixinStandardHelpOptions = true,
    usageHelpAutoWidth = true,
    subcommands = [SchedulerCommand::class, PairingCommand::class, AgentCommand::class, WorkflowCommand::class, TeamCommand::class, McpCommand::class, SkillCommand::class, ProjectCommand::class, DoctorCommand::class, ConfigCommand::class],
)
class GolemCli : Callable<Int> {
    @Spec
    lateinit var spec: CommandSpec

    @Option(
        names = ["--persona"],
        description = ["Path to AGENTS.md / CLAUDE.md persona file"],
    )
    var personaPath: String? = null

    @Option(
        names = ["--model"],
        description = ["LLM model to use (default: gpt-4o)"],
        defaultValue = "gpt-4o",
    )
    var model: String = "gpt-4o"

    @Option(
        names = ["--provider"],
        description = ["Provider name (openai, anthropic, openai-compat)"],
        defaultValue = "openai",
    )
    var provider: String = "openai"

    @Option(
        names = ["--verbose"],
        description = ["Enable verbose CLI output"],
    )
    var verbose: Boolean = false

    @Option(
        names = ["--debug"],
        description = ["Enable debug CLI output (implies --verbose)"],
    )
    var debug: Boolean = false

    @Option(
        names = ["--dir", "--workdir"],
        description = ["Working directory (default: current)"],
        defaultValue = ".",
    )
    var workdir: String = "."

    @Option(
        names = ["--memory-db"],
        description = ["Path to SQLite memory database"],
        defaultValue = "./.golem/memory.db",
    )
    var memoryDb: String = "./.golem/memory.db"

    @Option(
        names = ["--max-turns"],
        description = ["Maximum agent turns (default: 25)"],
        defaultValue = "25",
    )
    var maxTurns: Int = 25

    @Option(
        names = ["--scheduler-db"],
        description = ["Path to SQLite scheduler database"],
        defaultValue = "./.golem/scheduler.db",
    )
    var schedulerDb: String = "./.golem/scheduler.db"

    @Option(
        names = ["--kanban-db"],
        description = ["Path to SQLite kanban database"],
        defaultValue = "./.golem/kanban.db",
    )
    var kanbanDb: String = "./.golem/kanban.db"

    @Option(
        names = ["--jvm-index-db"],
        description = ["Path to SQLite JVM project index database"],
        defaultValue = "./.golem/jvm-index.db",
    )
    var jvmIndexDb: String = "./.golem/jvm-index.db"

    @Option(
        names = ["--skills-dir"],
        description = ["Path to the skills directory"],
        defaultValue = "",
    )
    var skillsDir: String = ""

    @Option(
        names = ["--mcp"],
        description = ["Run as MCP server instead of CLI"],
    )
    var mcpMode: Boolean = false

    @Option(
        names = ["--api"],
        description = ["Run as REST API server instead of CLI"],
    )
    var apiMode: Boolean = false

    @Option(
        names = ["--daemon"],
        description = ["Run the scheduler daemon instead of CLI/REPL"],
    )
    var daemonMode: Boolean = false

    @Option(
        names = ["--mcp-port"],
        description = ["MCP server port for SSE transport (default: 8091)"],
        defaultValue = "8091",
    )
    var mcpPort: Int = 8091

    @Option(
        names = ["--api-port"],
        description = ["REST API server port (default: 8082)"],
        defaultValue = "8082",
    )
    var apiPort: Int = 8082

    @Option(
        names = ["--api-key"],
        description = ["API key for REST API and MCP SSE auth"],
    )
    var apiKey: String? = null

    @Option(
        names = ["--insecure"],
        description = ["Allow binding to 0.0.0.0 without API key"],
    )
    var insecure: Boolean = false

    @Option(
        names = ["--resume", "--session-id"],
        description = ["Resume a previous agent run by session id"],
    )
    var sessionId: String? = null

    @Option(
        names = ["--mcp-transport"],
        description = ["MCP transport: stdio or sse (default: stdio)"],
        defaultValue = "stdio",
    )
    var mcpTransport: String = "stdio"

    @Option(
        names = ["--mcp-host"],
        description = ["MCP server host for SSE transport (default: 127.0.0.1)"],
        defaultValue = "127.0.0.1",
    )
    var mcpHost: String = "127.0.0.1"

    @Parameters(
        description = ["Optional goal (one-shot mode). Omit for interactive REPL."],
        arity = "0..1",
    )
    var goal: String? = null

    override fun call(): Int {
        val baseConfig = buildConfig(this)
        val apiConfig = if (apiMode) {
            baseConfig.copy(
                workflowDispatcherConfig = baseConfig.workflowDispatcherConfig.copy(enabled = true),
            )
        } else {
            baseConfig
        }
        val daemonConfig = if (daemonMode) {
            baseConfig.copy(
                workflowDispatcherConfig = baseConfig.workflowDispatcherConfig.copy(enabled = true),
            )
        } else {
            baseConfig
        }

        return runBlocking {
            try {
                if (mcpMode) {
                    runMcpServer(
                        port = mcpPort,
                        host = mcpHost,
                        transport = mcpTransport,
                        config = baseConfig,
                    )
                } else if (apiMode) {
                    GolemApiServer(
                        config = apiConfig,
                        port = apiPort,
                        insecure = insecure,
                    ).start(wait = true)
                } else if (daemonMode) {
                    runSchedulerDaemon(daemonConfig)
                } else if (goal != null) {
                    runOneShot(goal = goal!!, config = baseConfig)
                } else {
                    runRepl(config = baseConfig)
                }
                0
            } catch (e: Exception) {
                System.err.println("Fatal error: ${e::class.simpleName ?: e.message ?: "Unknown error"}")
                e.printStackTrace()
                1
            }
        }
    }
}

suspend fun <T> withAgentStore(root: GolemCli, block: suspend (dev.spola.agent.AgentStore) -> T): T {
    val agentDb = root.memoryDb.substringBeforeLast("/") + "/agents.db"
    val store = SqliteAgentStore(agentDb)
    return try {
        block(store)
    } finally {
        store.close()
    }
}

fun buildConfig(root: GolemCli): GolemConfig {
    val baseConfig = GolemConfigFileStore().load()
    val resolvedApiKey = when {
        root.optionMatched("--api-key") -> root.apiKey
        !System.getenv("GOLEM_API_KEY").isNullOrBlank() -> System.getenv("GOLEM_API_KEY")
        else -> baseConfig.apiKey
    }
    val resolvedVerbosity = when {
        root.debug -> Verbosity.DEBUG
        root.verbose -> Verbosity.VERBOSE
        else -> baseConfig.verbosity
    }

    return baseConfig.copy(
        model = root.overrideIfMatched("--model", root.model, baseConfig.model),
        provider = root.overrideIfMatched("--provider", root.provider, baseConfig.provider),
        verbosity = resolvedVerbosity,
        maxTurns = root.overrideIfMatched("--max-turns", root.maxTurns, baseConfig.maxTurns),
        workingDirectory = root.overrideIfMatched(arrayOf("--dir", "--workdir"), root.workdir, baseConfig.workingDirectory),
        personaPath = root.overrideIfMatched("--persona", root.personaPath, baseConfig.personaPath),
        memoryDbPath = root.overrideIfMatched("--memory-db", root.memoryDb, baseConfig.memoryDbPath),
        schedulerDbPath = root.overrideIfMatched("--scheduler-db", root.schedulerDb, baseConfig.schedulerDbPath),
        kanbanDbPath = root.overrideIfMatched("--kanban-db", root.kanbanDb, baseConfig.kanbanDbPath),
        jvmIndexDbPath = root.overrideIfMatched("--jvm-index-db", root.jvmIndexDb, baseConfig.jvmIndexDbPath),
        apiKey = resolvedApiKey,
        insecure = root.overrideIfMatched("--insecure", root.insecure, baseConfig.insecure),
        sessionId = root.overrideIfMatched(arrayOf("--resume", "--session-id"), root.sessionId, baseConfig.sessionId),
        skillsDir = root.overrideIfMatched("--skills-dir", root.skillsDir, baseConfig.skillsDir),
    )
}

suspend fun <T> withJobStore(root: GolemCli, block: suspend (GolemJobStore) -> T): T {
    val store = SqliteGolemJobStore(root.schedulerDb)
    return try {
        block(store)
    } finally {
        store.close()
    }
}

suspend fun runSchedulerDaemon(config: GolemConfig) {
    val jobStore = SqliteGolemJobStore(config.schedulerDbPath)
    val workflowStore = SqliteWorkflowExecutionStore(config.workflowDbPath)
    val workflowRegistry = WorkflowTemplateRegistry().apply {
        registerBuiltInTemplates()
        registerYamlWorkflows(config)
    }
    val workflowExecutionService = WorkflowExecutionService(
        config = config,
        executionStore = workflowStore,
        workflowRegistry = workflowRegistry,
    )
    val workflowSchedulerService = WorkflowSchedulerService(
        executionService = workflowExecutionService,
        config = config,
    )
    val scheduler = GolemScheduler(
        jobStore = jobStore,
        config = config,
        workflowSchedulerService = workflowSchedulerService,
    )
    val dispatcher = if (config.workflowDispatcherConfig.enabled) {
        AsyncWorkflowDispatcher(
            executionStore = workflowStore,
            executionService = workflowExecutionService,
            pollIntervalMs = config.workflowDispatcherConfig.pollIntervalMs,
            batchSize = config.workflowDispatcherConfig.batchSize,
            globalMaxConcurrent = config.workflowDispatcherConfig.globalMaxConcurrent,
            perUserMaxConcurrent = config.workflowDispatcherConfig.perUserMaxConcurrent,
        )
    } else {
        null
    }

    try {
        println("Scheduler daemon started. Polling ${config.schedulerDbPath}")
        dispatcher?.start()
        scheduler.start()
        awaitCancellation()
    } finally {
        scheduler.stop()
        dispatcher?.stop()
        workflowStore.close()
        jobStore.close()
    }
}

fun main(args: Array<String>) {
    CommandLine(GolemCli()).execute(*args)
}

private fun GolemCli.optionMatched(vararg names: String): Boolean {
    val parseResult = runCatching { spec.commandLine().parseResult }.getOrNull() ?: return false
    return names.any(parseResult::hasMatchedOption)
}

private fun <T> GolemCli.overrideIfMatched(name: String, cliValue: T, baseValue: T): T {
    return if (optionMatched(name)) cliValue else baseValue
}

private fun <T> GolemCli.overrideIfMatched(names: Array<String>, cliValue: T, baseValue: T): T {
    return if (optionMatched(*names)) cliValue else baseValue
}
