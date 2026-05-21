package dev.spola.cli

import dev.spola.SpolaConfig
import dev.spola.workflow.SpolaState
import dev.spola.workflow.JvmWorkflowTemplates
import dev.spola.workflow.SqliteWorkflowExecutionStore
import dev.spola.workflow.TeamWorkflowSteps
import dev.spola.workflow.TeamWorkflowSteps.parallelAgentsStep
import dev.spola.workflow.WorkflowExecutionService
import dev.spola.workflow.WorkflowTemplateRegistry
import dev.spola.workflow.NewWorkflowExecution
import dev.spola.workflow.registerBuiltInTemplates
import dev.spola.workflow.yaml.WorkflowExport
import dev.spola.workflow.yaml.YamlWorkflowLoader
import dev.tramai.orchestration.workflow
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

@Command(
    name = "workflow",
    description = ["Run and manage Spola workflows"],
    subcommands = [
        WorkflowRunCommand::class,
        WorkflowListCommand::class,
        WorkflowExportCommand::class,
        WorkflowApproveCommand::class,
    ],
)
class WorkflowCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: SpolaCli

    override fun call(): Int {
        CommandLine.usage(this, System.out)
        return 0
    }
}

@Command(name = "run", description = ["Run a workflow (built-in or YAML-defined)"])
class WorkflowRunCommand : Callable<Int> {
    @ParentCommand
    lateinit var workflowCommand: WorkflowCommand

    private val root get() = workflowCommand.parent

    @Parameters(index = "0", description = ["Workflow name (e.g., code-review, jvm-debug, or a YAML-defined workflow)"])
    lateinit var workflowName: String

    @Parameters(index = "1", description = ["Goal for the workflow"])
    lateinit var goal: String

    @Option(names = ["--param"], description = ["Parameters in key=value format (can be repeated)"])
    var params: List<String> = emptyList()

    override fun call(): Int = runBlocking {
        val config = buildConfig(root)

        // Build the registry with both built-in + YAML workflows
        val registry = WorkflowTemplateRegistry().apply {
            registerBuiltInTemplates()
            YamlWorkflowLoader.loadAndRegister(this, config)
        }

        val executionStore = SqliteWorkflowExecutionStore(config.workflowDbPath)
        val executionService = WorkflowExecutionService(
            config = config,
            executionStore = executionStore,
            workflowRegistry = registry,
        )

        val parametersJson = buildParametersJson(params)
        val escapedParams = parametersJson.replace("\"", "\\\"")

        val record = executionService.enqueue(
            NewWorkflowExecution(
                definitionId = null,
                workflowName = workflowName,
                triggerSource = "cli",
                inputJson = """{"goal":"${goal.replace("\"", "\\\"")}","parametersJson":"${escapedParams}"}""",
            )
        )

        val result = executionService.runExecution(record)
        println(result)
        0
    }

    private fun buildParametersJson(params: List<String>): String {
        if (params.isEmpty()) return "{}"
        val entries = params.map { param ->
            val (key, value) = param.split("=", limit = 2)
            """"${key}":"${value.replace("\"", "\\\"")}""""
        }
        return "{${entries.joinToString(",")}}"
    }
}

@Command(name = "list", description = ["List available workflows"])
class WorkflowListCommand : Callable<Int> {
    @ParentCommand
    lateinit var workflowCommand: WorkflowCommand

    private val root get() = workflowCommand.parent

    override fun call(): Int = runBlocking {
        val config = buildConfig(root)
        val registry = WorkflowTemplateRegistry().apply {
            registerBuiltInTemplates()
            YamlWorkflowLoader.loadAndRegister(this, config)
        }

        val workflows = registry.list()
        if (workflows.isEmpty()) {
            println("No workflows available.")
        } else {
            println("Available workflows:")
            for (wf in workflows) {
                val source = when {
                    wf.name in listOf("code-review", "jvm-debug", "jvm-refactor", "jvm-migration") ->
                        "[built-in]"
                    else -> "[yaml]"
                }
                println("  - ${wf.name} (v${wf.version}) $source")
            }
        }
        0
    }
}

@Command(name = "export", description = ["Export a built-in workflow template as YAML"])
class WorkflowExportCommand : Callable<Int> {
    @ParentCommand
    lateinit var workflowCommand: WorkflowCommand

    private val root get() = workflowCommand.parent

    @Parameters(index = "0", description = ["Workflow name to export (e.g., jvm-debug)"])
    lateinit var workflowName: String

    @Option(names = ["--output", "-o"], description = ["Output file path (default: stdout)"])
    var outputPath: String? = null

    override fun call(): Int = runBlocking {
        val config = buildConfig(root)
        val registry = WorkflowTemplateRegistry().apply {
            registerBuiltInTemplates()
            YamlWorkflowLoader.loadAndRegister(this, config)
        }

        val yaml = WorkflowExport.exportTemplate(registry, workflowName)
            ?: run {
                System.err.println("Unknown workflow: $workflowName")
                return@runBlocking 1
            }

        if (outputPath != null) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(outputPath), yaml)
            println("Exported to $outputPath")
        } else {
            println(yaml)
        }
        0
    }
}

@Command(name = "approve", description = ["Approve a workflow execution waiting for human approval"])
class WorkflowApproveCommand : Callable<Int> {
    @ParentCommand
    lateinit var workflowCommand: WorkflowCommand

    private val root get() = workflowCommand.parent

    @Parameters(index = "0", description = ["Execution ID to approve"])
    lateinit var executionId: String

    override fun call(): Int = runBlocking {
        val config = buildConfig(root)
        val executionStore = SqliteWorkflowExecutionStore(config.workflowDbPath)
        val registry = WorkflowTemplateRegistry().apply {
            registerBuiltInTemplates()
            YamlWorkflowLoader.loadAndRegister(this, config)
        }
        val executionService = WorkflowExecutionService(
            config = config,
            executionStore = executionStore,
            workflowRegistry = registry,
        )
        return@runBlocking try {
            executionService.approveExecution(executionId)
            println("Execution $executionId approved and resumed.")
            0
        } catch (t: Throwable) {
            System.err.println("Failed to approve execution $executionId: ${t.message}")
            1
        }
    }
}

@Command(
    name = "team",
    description = ["Run a team of agents in parallel"],
    subcommands = [TeamRunCommand::class],
)
class TeamCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: SpolaCli

    override fun call(): Int {
        CommandLine.usage(this, System.out)
        return 0
    }
}

@Command(name = "run", description = ["Run a team of agents"])
class TeamRunCommand : Callable<Int> {
    @ParentCommand
    lateinit var teamCommand: TeamCommand

    private val root get() = teamCommand.parent

    @Option(names = ["--agents"], description = ["Comma-separated agent IDs"], required = true)
    var agents: String = ""

    @Option(names = ["--goal"], description = ["Goal for the team"], required = true)
    var goal: String = ""

    override fun call(): Int = runBlocking {
        val agentIds = agents.split(",").map { it.trim() }
        val config = buildConfig(root)
        val wf = workflow<SpolaState>("team-run", "1") {
            parallelAgentsStep(
                name = "team",
                agents = agentIds,
                goal = { it.goal },
                config = config,
            )
        }.build { it.result ?: "no result" }
        val result = wf.run(initialState = SpolaState.initial(goal = goal, config = config))
        println(result)
        0
    }
}
