package dev.spola.cli

import dev.spola.GolemFactory
import dev.spola.agent.AgentDefinition
import dev.spola.agent.AgentLoader
import dev.spola.agent.SqliteAgentStore
import dev.spola.agent.ToolPolicy
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

@Command(
    name = "agent",
    description = ["Manage custom agent definitions"],
    subcommands = [
        AgentListCommand::class,
        AgentShowCommand::class,
        AgentCreateCommand::class,
        AgentUpdateCommand::class,
        AgentDeleteCommand::class,
        AgentRunCommand::class,
    ],
)
class AgentCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: GolemCli

    override fun call(): Int {
        CommandLine.usage(this, System.out)
        return 0
    }
}

@Command(name = "list", description = ["List all custom agents"])
class AgentListCommand : Callable<Int> {
    @ParentCommand
    lateinit var agentCommand: AgentCommand

    override fun call(): Int = runBlocking {
        withAgentStore(agentCommand.root) { store ->
            val agents = store.list()
            if (agents.isEmpty()) {
                println("No custom agents defined.")
            } else {
                println("Custom Agents:")
                println("\u2500".repeat(60))
                for (a in agents) {
                    val status = if (a.enabled) "\u2705" else "\u26D4"
                    println("$status ${a.id} \u2014 ${a.name}")
                    println("  ${a.preferredProvider}/${a.preferredModel}")
                    if (a.description.isNotBlank()) println("  ${a.description}")
                    println()
                }
                println("${agents.size} agent(s)")
            }
        }
        0
    }
}

@Command(name = "show", description = ["Show full agent definition"])
class AgentShowCommand : Callable<Int> {
    @ParentCommand
    lateinit var agentCommand: AgentCommand

    @Parameters(index = "0", description = ["Agent id"])
    lateinit var agentId: String

    override fun call(): Int = runBlocking {
        withAgentStore(agentCommand.root) { store ->
            val agent = store.get(agentId)
                ?: return@withAgentStore run {
                    System.err.println("Agent not found: $agentId")
                    1
                }

            println("Agent: ${agent.id} (v${agent.version})")
            println("Name: ${agent.name}")
            println("Description: ${agent.description}")
            println()
            println("\u2014\u2014 Model \u2014\u2014")
            println("Preferred: ${agent.preferredProvider}/${agent.preferredModel}")
            agent.fallbackModel?.let { println("Fallback:   ${agent.fallbackProvider ?: agent.preferredProvider}/$it") }
            agent.temperature?.let { println("Temperature: $it") }
            agent.maxTokens?.let { println("Max Tokens: $it") }
            println()
            println("\u2014\u2014 Permissions \u2014\u2014")
            println("Filesystem: ${agent.filesystemAccess}")
            println("Shell: ${agent.shellAccess}")
            println("Network: ${agent.networkAccess}")
            println("Execute: ${agent.executeCommands}")
            println("Memory: ${agent.memoryScope}")
            println()
            println("\u2014\u2014 Output \u2014\u2014")
            println("Format: ${agent.responseFormat}")
            println("Tags: ${agent.tags.joinToString(", ")}")
            println()
            println("\u2014\u2014 Instructions \u2014\u2014")
            println(agent.systemPrompt)
            0
        }
    }
}

@Command(name = "create", description = ["Create a new custom agent"])
class AgentCreateCommand : Callable<Int> {
    @ParentCommand
    lateinit var agentCommand: AgentCommand

    @Option(names = ["--id"], required = true, description = ["Unique agent id"])
    lateinit var agentId: String

    @Option(names = ["--name"], required = true, description = ["Human-readable name"])
    lateinit var name: String

    @Option(names = ["--desc"], description = ["Short description"])
    var description: String = ""

    @Option(names = ["--system-prompt", "-i"], required = true, description = ["System prompt / persona"])
    lateinit var systemPrompt: String

    @Option(names = ["--model"], required = true, description = ["Preferred model (e.g., claude-sonnet-4)"])
    lateinit var model: String

    @Option(names = ["--provider"], required = true, description = ["Preferred provider (openai, anthropic, openai-compat, ollama, google)"])
    lateinit var provider: String

    @Option(names = ["--fallback-model"], description = ["Fallback model"])
    var fallbackModel: String? = null

    @Option(names = ["--temp"], description = ["Temperature (0.0-2.0)"])
    var temperature: Double? = null

    @Option(names = ["--fs"], description = ["Filesystem access: read-write, read-only, none"])
    var filesystemAccess: String = "read-write"

    @Option(names = ["--shell"], description = ["Shell access allowed (true/false)"])
    var shellAccess: String = "true"

    @Option(names = ["--network"], description = ["Network access allowed (true/false)"])
    var networkAccess: String = "true"

    @Option(names = ["--exec"], description = ["Execute mode: auto, ask_first, never"])
    var executeCommands: String = "auto"

    @Option(names = ["--memory"], description = ["Memory scope: global, agent, none"])
    var memoryScope: String = "global"

    @Option(names = ["--tags"], split = ",", description = ["Comma-separated tags"])
    var tags: List<String> = emptyList()

    override fun call(): Int = runBlocking {
        withAgentStore(agentCommand.root) { store ->
            val agent = AgentDefinition(
                id = agentId,
                name = name,
                description = description,
                systemPrompt = systemPrompt,
                preferredModel = model,
                preferredProvider = provider,
                fallbackModel = fallbackModel,
                temperature = temperature,
                toolPolicy = ToolPolicy.ALL,
                filesystemAccess = filesystemAccess,
                shellAccess = shellAccess.toBoolean(),
                networkAccess = networkAccess.toBoolean(),
                executeCommands = executeCommands,
                memoryScope = memoryScope,
                tags = tags,
                memoryNamespace = if (memoryScope == "agent") agentId else null,
            )

            try {
                store.create(agent)
                println("\u2705 Created agent '${agent.id}' (${agent.name})")

                // Also write YAML file if AgentLoader is available
                if (AgentLoader.isAvailable) {
                    val dir = java.nio.file.Path.of(agentCommand.root.workdir).resolve(".golem/agents")
                    AgentLoader.writeToFile(dir, agent)
                    println("   Written to $dir/${agent.id}.yaml")
                }
            } catch (e: Exception) {
                System.err.println("\u274C ${e.message}")
                return@withAgentStore 1
            }
            0
        }
    }
}

@Command(name = "update", description = ["Update an existing custom agent"])
class AgentUpdateCommand : Callable<Int> {
    @ParentCommand
    lateinit var agentCommand: AgentCommand

    @Parameters(index = "0", description = ["Agent id"])
    lateinit var agentId: String

    @Option(names = ["--name"], description = ["New name"])
    var name: String? = null

    @Option(names = ["--desc"], description = ["New description"])
    var description: String? = null

    @Option(names = ["--system-prompt", "-i"], description = ["New system prompt"])
    var systemPrompt: String? = null

    @Option(names = ["--model"], description = ["New preferred model"])
    var model: String? = null

    @Option(names = ["--provider"], description = ["New preferred provider"])
    var provider: String? = null

    @Option(names = ["--fs"], description = ["Filesystem access"])
    var filesystemAccess: String? = null

    @Option(names = ["--enable"], description = ["Enable agent"])
    var enable: Boolean? = null

    override fun call(): Int = runBlocking {
        withAgentStore(agentCommand.root) { store ->
            val existing = store.get(agentId) ?: return@withAgentStore run {
                System.err.println("Agent not found: $agentId")
                1
            }

            val updated = existing.copy(
                name = name ?: existing.name,
                description = description ?: existing.description,
                systemPrompt = systemPrompt ?: existing.systemPrompt,
                preferredModel = model ?: existing.preferredModel,
                preferredProvider = provider ?: existing.preferredProvider,
                filesystemAccess = filesystemAccess ?: existing.filesystemAccess,
                enabled = enable ?: existing.enabled,
                version = existing.version + 1,
            )

            store.update(updated)
            println("\u2705 Updated agent '$agentId' (v${updated.version})")
            0
        }
    }
}

@Command(name = "delete", aliases = ["rm"], description = ["Delete a custom agent"])
class AgentDeleteCommand : Callable<Int> {
    @ParentCommand
    lateinit var agentCommand: AgentCommand

    @Parameters(index = "0", description = ["Agent id to delete"])
    lateinit var agentId: String

    override fun call(): Int = runBlocking {
        withAgentStore(agentCommand.root) { store ->
            if (store.delete(agentId)) {
                AgentLoader.deleteFile(java.nio.file.Path.of(".golem/agents"), agentId)
                println("\u2705 Deleted agent '$agentId'")
                0
            } else {
                System.err.println("Agent not found: $agentId")
                1
            }
        }
    }
}

@Command(name = "run", description = ["Run a custom agent with a goal"])
class AgentRunCommand : Callable<Int> {
    @ParentCommand
    lateinit var agentCommand: AgentCommand

    @Parameters(index = "0", description = ["Agent id to run"])
    lateinit var agentId: String

    @Parameters(index = "1", description = ["Goal for the agent"])
    lateinit var goal: String

    override fun call(): Int = runBlocking {
        withAgentStore(agentCommand.root) { store ->
            val agentDef = store.get(agentId)
                ?: return@withAgentStore run {
                    System.err.println("Agent not found: $agentId")
                    1
                }

            if (!agentDef.enabled) {
                System.err.println("Agent '$agentId' is disabled")
                return@withAgentStore 1
            }

            println("Running agent '${agentDef.name}' (${agentDef.preferredProvider}/${agentDef.preferredModel})...")
            println("Goal: $goal")
            println()

            val config = buildConfig(agentCommand.root)

            try {
                val instance = GolemFactory.createFromAgentDefinition(
                    agentDef = agentDef,
                    config = config,
                )
                val result = instance.run(goal)
                println()
                println("\u2014\u2014 Result \u2014\u2014")
                println(result)
                instance.close()
                0
            } catch (e: Exception) {
                System.err.println("\u274C Agent run failed: ${e.message}")
                e.printStackTrace()
                1
            }
        }
    }
}
