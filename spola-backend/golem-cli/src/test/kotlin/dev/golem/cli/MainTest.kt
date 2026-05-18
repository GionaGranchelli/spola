package dev.spola.cli

import dev.spola.Verbosity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import kotlin.test.assertTrue

/**
 * CLI tests for [GolemCli] using picocli's programmatic invocation.
 *
 * These tests verify flag parsing, config mapping, and CLI option defaults.
 * They do NOT run the actual agent or API server.
 */
class MainTest {

    @Test
    fun `GolemCli executes with --version flag`() {
        val root = CommandLine(GolemCli())
        val out = ByteArrayOutputStream()
        root.setOut(PrintWriter(out))
        root.setErr(PrintWriter(ByteArrayOutputStream()))
        val exitCode = root.execute("--version")
        assertTrue(exitCode == 0)
        assertTrue(out.toString().contains("0.1.0"))
    }

    @Test
    fun `GolemCli executes with --help flag`() {
        val root = CommandLine(GolemCli())
        val out = ByteArrayOutputStream()
        root.setOut(PrintWriter(out))
        root.setErr(PrintWriter(ByteArrayOutputStream()))
        val exitCode = root.execute("--help")
        assertTrue(exitCode == 0)
        val text = out.toString()
        assertTrue(text.contains("golem"))
        assertTrue(text.contains("Golem"))
    }

    @Test
    fun `GolemCli with --api-key sets config apiKey`() {
        val cli = GolemCli()
        CommandLine(cli).parseArgs("--api-key", "test-key-123")
        assertTrue(cli.apiKey == "test-key-123")
    }

    @Test
    fun `GolemCli with workdir sets config workingDirectory`() {
        val cli = GolemCli()
        CommandLine(cli).parseArgs("--workdir", "/tmp/test-workdir")
        assertTrue(cli.workdir == "/tmp/test-workdir")
    }

    @Test
    fun `GolemCli with --insecure sets insecure to true`() {
        val cli = GolemCli()
        CommandLine(cli).parseArgs("--insecure")
        assertTrue(cli.insecure)
    }

    @Test
    fun `GolemCli default insecure is false`() {
        val cli = GolemCli()
        assertTrue(!cli.insecure)
    }

    @Test
    fun `GolemCli with --persona sets personaPath`() {
        val cli = GolemCli()
        CommandLine(cli).parseArgs("--persona", "./my-agent.md")
        assertTrue(cli.personaPath == "./my-agent.md")
    }

    @Test
    fun `GolemCli with --model sets model`() {
        val cli = GolemCli()
        CommandLine(cli).parseArgs("--model", "claude-sonnet-4")
        assertTrue(cli.model == "claude-sonnet-4")
    }

    @Test
    fun `GolemCli with --provider sets provider`() {
        val cli = GolemCli()
        CommandLine(cli).parseArgs("--provider", "anthropic")
        assertTrue(cli.provider == "anthropic")
    }

    @Test
    fun `GolemCli with --skills-dir sets config skillsDir`() {
        val cli = GolemCli()
        CommandLine(cli).parseArgs("--skills-dir", "/tmp/custom-skills")

        val config = buildConfig(cli)

        assertThat(config.skillsDir).isEqualTo("/tmp/custom-skills")
    }

    @Test
    fun `GolemCli registers all expected subcommands`() {
        val cmd = CommandLine(GolemCli())
        assertTrue(cmd.subcommands.containsKey("scheduler"))
        assertTrue(cmd.subcommands.containsKey("pairing"))
        assertTrue(cmd.subcommands.containsKey("agent"))
        assertTrue(cmd.subcommands.containsKey("workflow"))
        assertTrue(cmd.subcommands.containsKey("team"))
        assertTrue(cmd.subcommands.containsKey("mcp"))
        assertTrue(cmd.subcommands.containsKey("skill"))
        assertTrue(cmd.subcommands.containsKey("project"))
        assertTrue(cmd.subcommands.containsKey("doctor"))
        assertTrue(cmd.subcommands.containsKey("config"))
    }

    @Test
    fun `GolemCli builds GolemConfig from options`() {
        val cli = GolemCli()
        CommandLine(cli).parseArgs(
            "--api-key", "my-key",
            "--workdir", "/my/workdir",
            "--insecure",
            "--model", "gpt-4o-mini",
            "--provider", "openai",
            "--skills-dir", "/my/skills",
        )
        val config = buildConfig(cli)
        assertTrue(config.model == "gpt-4o-mini")
        assertTrue(config.provider == "openai")
        assertTrue(config.workingDirectory == "/my/workdir")
        assertTrue(config.apiKey == "my-key")
        assertTrue(config.insecure)
        assertThat(config.skillsDir).isEqualTo("/my/skills")
    }

    @Test
    fun `GolemCli default workdir is dot`() {
        val cli = GolemCli()
        assertTrue(cli.workdir == ".")
    }

    @Test
    fun `GolemCli default apiKey is null`() {
        val cli = GolemCli()
        assertTrue(cli.apiKey == null)
    }

    @Test
    fun `GolemCli with verbose flag sets verbosity`() {
        val cli = GolemCli()
        CommandLine(cli).parseArgs("--verbose")

        assertThat(buildConfig(cli).verbosity).isEqualTo(Verbosity.VERBOSE)
    }

    @Test
    fun `GolemCli with debug flag overrides verbose`() {
        val cli = GolemCli()
        CommandLine(cli).parseArgs("--verbose", "--debug")

        assertThat(buildConfig(cli).verbosity).isEqualTo(Verbosity.DEBUG)
    }
}
