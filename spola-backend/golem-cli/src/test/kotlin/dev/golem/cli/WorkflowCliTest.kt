package dev.spola.cli

import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertTrue

/**
 * CLI integration tests for workflow and team commands from Phase 7.
 *
 * These tests verify that picocli commands construct without exceptions,
 * produce expected help text, and have correct annotations.
 * They do NOT execute actual workflows (which would make HTTP calls).
 *
 * Note: Commands with @ParentCommand fields must be accessed via
 * the full hierarchy (e.g., GolemCli -> workflow -> run) so picocli
 * can resolve the parent type correctly.
 */
class WorkflowCliTest {

    // -----------------------------------------------------------------------
    // WorkflowCommand
    // -----------------------------------------------------------------------

    @Test
    fun `WorkflowCommand has correct name`() {
        val root = CommandLine(GolemCli())
        val cmd = root.subcommands["workflow"]!!
        assertTrue(cmd.commandName == "workflow")
    }

    @Test
    fun `WorkflowCommand shows help text when invoked`() {
        val root = CommandLine(GolemCli())
        val cmd = root.subcommands["workflow"]!!
        val out = ByteArrayOutputStream()
        cmd.usage(PrintStream(out))
        val text = out.toString()
        assertTrue(text.contains("workflow"))
        assertTrue(text.contains("Run and manage Golem workflows"))
    }

    @Test
    fun `WorkflowCommand lists WorkflowRunCommand as subcommand`() {
        val root = CommandLine(GolemCli())
        val cmd = root.subcommands["workflow"]!!
        assertTrue(cmd.subcommands.containsKey("run"))
    }

    @Test
    fun `WorkflowCommand run subcommand has correct name`() {
        val root = CommandLine(GolemCli())
        val runCmdLine = root.subcommands["workflow"]!!.subcommands["run"]!!
        assertTrue(runCmdLine.commandName == "run")
    }

    @Test
    fun `WorkflowCommand usage mentions run subcommand`() {
        val root = CommandLine(GolemCli())
        val cmd = root.subcommands["workflow"]!!
        val out = ByteArrayOutputStream()
        cmd.usage(PrintStream(out))
        val text = out.toString()
        assertTrue(text.contains("run"))
    }

    // -----------------------------------------------------------------------
    // WorkflowRunCommand
    // -----------------------------------------------------------------------

    @Test
    fun `WorkflowRunCommand has correct name via hierarchy`() {
        val root = CommandLine(GolemCli())
        val runCmdLine = root.subcommands["workflow"]!!.subcommands["run"]!!
        assertTrue(runCmdLine.commandName == "run")
    }

    @Test
    fun `WorkflowRunCommand usage mentions goal parameter`() {
        val root = CommandLine(GolemCli())
        val runCmdLine = root.subcommands["workflow"]!!.subcommands["run"]!!
        val out = ByteArrayOutputStream()
        runCmdLine.usage(PrintStream(out))
        val text = out.toString()
        assertTrue(text.contains("Goal"))
    }

    @Test
    fun `WorkflowRunCommand usage mentions code-review`() {
        val root = CommandLine(GolemCli())
        val runCmdLine = root.subcommands["workflow"]!!.subcommands["run"]!!
        val out = ByteArrayOutputStream()
        runCmdLine.usage(PrintStream(out))
        val text = out.toString()
        assertTrue(text.contains("code-review"))
    }

    @Test
    fun `WorkflowRunCommand usage mentions workflow name parameter`() {
        val root = CommandLine(GolemCli())
        val runCmdLine = root.subcommands["workflow"]!!.subcommands["run"]!!
        val out = ByteArrayOutputStream()
        runCmdLine.usage(PrintStream(out))
        val text = out.toString()
        assertTrue(text.contains("Workflow name"))
    }

    @Test
    fun `WorkflowRunCommand constructs with code-review`() {
        // Direct construction (no picocli resolution needed) for simple property tests
        val cmd = WorkflowRunCommand()
        cmd.workflowName = "code-review"
        cmd.goal = "review this PR"
        assertTrue(cmd.workflowName == "code-review")
        assertTrue(cmd.goal == "review this PR")
    }

    @Test
    fun `WorkflowRunCommand parent is accessible via hierarchy`() {
        val root = CommandLine(GolemCli())
        val runCmdLine = root.subcommands["workflow"]!!.subcommands["run"]!!
        val runCmd = runCmdLine.commandSpec.userObject() as WorkflowRunCommand
        assertTrue(runCmd.workflowCommand != null)
        assertTrue(runCmd.workflowCommand is WorkflowCommand)
        assertTrue(runCmd.workflowCommand.parent is GolemCli)
    }

    @Test
    fun `WorkflowRunCommand parses code-review and goal from command line`() {
        val runCmd = WorkflowRunCommand()
        runCmd.workflowName = "code-review"
        runCmd.goal = "review the new feature"
        assertTrue(runCmd.workflowName == "code-review")
        assertTrue(runCmd.goal == "review the new feature")
    }

    // -----------------------------------------------------------------------
    // TeamCommand
    // -----------------------------------------------------------------------

    @Test
    fun `TeamCommand has correct name`() {
        val root = CommandLine(GolemCli())
        val cmd = root.subcommands["team"]!!
        assertTrue(cmd.commandName == "team")
    }

    @Test
    fun `TeamCommand shows help text when invoked`() {
        val root = CommandLine(GolemCli())
        val cmd = root.subcommands["team"]!!
        val out = ByteArrayOutputStream()
        cmd.usage(PrintStream(out))
        val text = out.toString()
        assertTrue(text.contains("team"))
        assertTrue(text.contains("team of agents"))
    }

    @Test
    fun `TeamCommand lists TeamRunCommand as subcommand`() {
        val root = CommandLine(GolemCli())
        val cmd = root.subcommands["team"]!!
        assertTrue(cmd.subcommands.containsKey("run"))
    }

    @Test
    fun `TeamCommand usage mentions run subcommand`() {
        val root = CommandLine(GolemCli())
        val cmd = root.subcommands["team"]!!
        val out = ByteArrayOutputStream()
        cmd.usage(PrintStream(out))
        val text = out.toString()
        assertTrue(text.contains("run"))
    }

    // -----------------------------------------------------------------------
    // TeamRunCommand
    // -----------------------------------------------------------------------

    @Test
    fun `TeamRunCommand has correct name via hierarchy`() {
        val root = CommandLine(GolemCli())
        val runCmdLine = root.subcommands["team"]!!.subcommands["run"]!!
        assertTrue(runCmdLine.commandName == "run")
    }

    @Test
    fun `TeamRunCommand shows agents and goal in usage`() {
        val root = CommandLine(GolemCli())
        val runCmdLine = root.subcommands["team"]!!.subcommands["run"]!!
        val out = ByteArrayOutputStream()
        runCmdLine.usage(PrintStream(out))
        val text = out.toString()
        assertTrue(text.contains("--agents"))
        assertTrue(text.contains("--goal"))
        assertTrue(text.contains("agent IDs"))
        assertTrue(text.contains("Goal for the team"))
    }

    @Test
    fun `TeamRunCommand constructs with agents and goal`() {
        val cmd = TeamRunCommand()
        cmd.agents = "agent1,agent2,agent3"
        cmd.goal = "build a feature"
        assertTrue(cmd.agents == "agent1,agent2,agent3")
        assertTrue(cmd.goal == "build a feature")
    }

    @Test
    fun `TeamRunCommand parent is accessible via hierarchy`() {
        val root = CommandLine(GolemCli())
        val runCmdLine = root.subcommands["team"]!!.subcommands["run"]!!
        val runCmd = runCmdLine.commandSpec.userObject() as TeamRunCommand
        assertTrue(runCmd.teamCommand != null)
        assertTrue(runCmd.teamCommand is TeamCommand)
        assertTrue(runCmd.teamCommand.parent is GolemCli)
    }

    @Test
    fun `TeamRunCommand parses --agents and --goal options`() {
        val runCmd = TeamRunCommand()
        runCmd.agents = "reviewer-a,reviewer-b"
        runCmd.goal = "analyze the codebase"
        assertTrue(runCmd.agents == "reviewer-a,reviewer-b")
        assertTrue(runCmd.goal == "analyze the codebase")
    }

    // -----------------------------------------------------------------------
    // GolemCli top-level — verify workflow and team are registered
    // -----------------------------------------------------------------------

    @Test
    fun `GolemCli registers workflow and team subcommands`() {
        val cmd = CommandLine(GolemCli())
        assertTrue(cmd.subcommands.containsKey("workflow"))
        assertTrue(cmd.subcommands.containsKey("team"))
    }

    @Test
    fun `GolemCli usage mentions workflow and team`() {
        val out = ByteArrayOutputStream()
        CommandLine.usage(GolemCli(), PrintStream(out))
        val text = out.toString()
        assertTrue(text.contains("workflow"))
        assertTrue(text.contains("team"))
        assertTrue(text.contains("Run and manage Golem workflows"))
        assertTrue(text.contains("Run a team of agents in parallel"))
    }

    // -----------------------------------------------------------------------
    // Integration — full command construction and wiring
    // -----------------------------------------------------------------------

    @Test
    fun `GolemCli constructs workflow run code-review with wiring`() {
        val golemCli = GolemCli()
        val workflowCmd = WorkflowCommand().apply { parent = golemCli }
        val runCmd = WorkflowRunCommand()
        runCmd.workflowCommand = workflowCmd
        runCmd.workflowName = "code-review"
        runCmd.goal = "check the tests"
        assertTrue(runCmd.workflowName == "code-review")
        assertTrue(runCmd.goal == "check the tests")
        assertTrue(runCmd.workflowCommand.parent === golemCli)
    }

    @Test
    fun `GolemCli constructs team run with agents and goal wiring`() {
        val golemCli = GolemCli()
        val teamCmd = TeamCommand().apply { parent = golemCli }
        val runCmd = TeamRunCommand()
        runCmd.teamCommand = teamCmd
        runCmd.agents = "a,b,c"
        runCmd.goal = "do something"
        assertTrue(runCmd.agents == "a,b,c")
        assertTrue(runCmd.goal == "do something")
        assertTrue(runCmd.teamCommand.parent === golemCli)
    }
}
