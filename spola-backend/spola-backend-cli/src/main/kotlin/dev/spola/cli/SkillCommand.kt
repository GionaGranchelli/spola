package dev.spola.cli

import dev.spola.SpolaFactory
import dev.spola.skill.SkillLoader
import dev.spola.skill.SkillDefinition
import kotlinx.coroutines.runBlocking
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

/**
 * CLI commands for managing and running skills.
 *
 * Skills are reusable agent capabilities defined as YAML files
 * in ~/.spola/skills/.
 */
@Command(
    name = "skill",
    description = ["Manage and run reusable agent skills"],
    subcommands = [
        SkillListCommand::class,
        SkillRunCommand::class,
        SkillInstallCommand::class,
    ],
)
class SkillCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: SpolaCli

    override fun call(): Int {
        picocli.CommandLine.usage(this, System.out)
        return 0
    }
}

@Command(name = "list", description = ["List all installed skills"])
class SkillListCommand : Callable<Int> {
    @ParentCommand
    lateinit var skillCommand: SkillCommand

    override fun call(): Int {
        val skillsDir = skillsDirectory(skillCommand.parent)
        val skills = SkillLoader.loadFromDirectory(skillsDir)

        if (skills.isEmpty()) {
            println("No skills installed.")
            println("Place .yaml files in $skillsDir or use 'spola skill install <path>'")
            return 0
        }

        println("Installed Skills ($skillsDir):")
        println("─".repeat(60))
        for (s in skills) {
            val tagsStr = if (s.tags.isNotEmpty()) " [${s.tags.joinToString(", ")}]" else ""
            val catStr = if (s.category.isNotBlank()) " (${s.category})" else ""
            println("• ${s.name} — ${s.description}$tagsStr$catStr")
        }
        println()
        println("${skills.size} skill(s)")
        return 0
    }
}

@Command(name = "run", description = ["Run a skill with a goal"])
class SkillRunCommand : Callable<Int> {
    @ParentCommand
    lateinit var skillCommand: SkillCommand

    @Parameters(index = "0", description = ["Skill name to run"])
    lateinit var skillName: String

    @Parameters(index = "1", description = ["Goal for the skill agent"])
    lateinit var goal: String

    override fun call(): Int = runBlocking {
        val skillsDir = skillsDirectory(skillCommand.parent)
        val skill = SkillLoader.loadFromDirectory(skillsDir)
            .firstOrNull { it.name.equals(skillName, ignoreCase = true) }
            ?: run {
                System.err.println("Skill '$skillName' not found in $skillsDir")
                val available = SkillLoader.loadFromDirectory(skillsDir)
                    .joinToString(", ") { it.name }
                if (available.isNotBlank()) {
                    System.err.println("Available: $available")
                }
                return@runBlocking 1
            }

        println("Running skill '${skill.name}': ${skill.description}")
        println("Goal: $goal")
        println()

        try {
            val config = buildConfig(skillCommand.parent)
            val instance = SpolaFactory.create(config = config)
            try {
                val result = instance.agent.run(
                    persona = skill.body,
                    goal = goal,
                    observer = instance.observer,
                )
                println("── Result ──")
                println(result)
            } finally {
                instance.close()
            }
            0
        } catch (e: Exception) {
            System.err.println("❌ Skill run failed: ${e.message}")
            e.printStackTrace()
            1
        }
    }
}

@Command(name = "install", description = ["Install a skill YAML file"])
class SkillInstallCommand : Callable<Int> {
    @ParentCommand
    lateinit var skillCommand: SkillCommand

    @Parameters(index = "0", description = ["Path to skill YAML file to install"])
    lateinit var sourcePath: String

    override fun call(): Int {
        val source = Path.of(sourcePath)
        if (!Files.exists(source)) {
            System.err.println("File not found: $source")
            return 1
        }
        if (!source.toString().endsWith(".yaml") && !source.toString().endsWith(".yml")) {
            System.err.println("Skill file must have .yaml or .yml extension")
            return 1
        }

        // Validate by trying to load it
        val skill = SkillLoader.loadFromFile(source)
        if (skill == null) {
            System.err.println("Invalid skill YAML: $source")
            return 1
        }

        val skillsDir = skillsDirectory(skillCommand.parent)
        Files.createDirectories(skillsDir)

        val targetName = skill.name.ifBlank {
            source.fileName.toString().removeSuffix(".yaml").removeSuffix(".yml")
        }
        val target = skillsDir.resolve("$targetName.yaml")

        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        println("✅ Installed skill '${skill.name}' to $target")
        return 0
    }
}

/**
 * Resolve the skills directory from CLI options or default.
 */
private fun skillsDirectory(root: SpolaCli): Path {
    val configured = buildConfig(root).skillsDir
    return if (configured.isBlank()) {
        Path.of(System.getProperty("user.home"), ".spola", "skills")
    } else {
        Path.of(configured)
    }
}
