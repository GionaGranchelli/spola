package dev.spola.cli

import dev.spola.SpolaFactory
import dev.spola.skill.SkillLoader
import dev.spola.skill.SkillDefinition
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.Callable

/**
 * CLI commands for managing and running skills.
 *
 * Skills are reusable agent capabilities defined as YAML files or SKILL.md files
 * in ~/.spola/skills/. They can also be installed from the Spola marketplace
 * on GitHub (github.com/spola-skills).
 */
@Command(
    name = "skill",
    description = ["Manage and run reusable agent skills"],
    subcommands = [
        SkillListCommand::class,
        SkillRunCommand::class,
        SkillInstallCommand::class,
        SkillSearchCommand::class,
        SkillPublishCommand::class,
    ],
)
class SkillCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: SpolaCli

    override fun call(): Int {
        CommandLine.usage(this, System.out)
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

@Command(name = "install", description = ["Install a skill from a local file or the marketplace"])
class SkillInstallCommand : Callable<Int> {
    @ParentCommand
    lateinit var skillCommand: SkillCommand

    @Parameters(index = "0", description = ["Skill name (from marketplace) or path to skill file (.yaml/.yml)"])
    lateinit var source: String

    @Option(
        names = ["--from-github"],
        description = ["Force install from GitHub marketplace even if source looks like a file path"],
    )
    var fromGithub: Boolean = false

    override fun call(): Int {
        val skillsDir = skillsDirectory(skillCommand.parent)

        // Auto-detect: if explicitly forced from GitHub, treat as marketplace name
        if (fromGithub) {
            return installFromMarketplace(source, skillsDir)
        }

        // If source ends with .yaml or .yml, treat as local file
        if (source.endsWith(".yaml") || source.endsWith(".yml")) {
            return installFromLocalFile(Path.of(source), skillsDir)
        }

        // Otherwise, it's a marketplace name
        return installFromMarketplace(source, skillsDir)
    }

    private fun installFromLocalFile(source: Path, skillsDir: Path): Int {
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

        Files.createDirectories(skillsDir)

        val targetName = skill.name.ifBlank {
            source.fileName.toString().removeSuffix(".yaml").removeSuffix(".yml")
        }
        val target = skillsDir.resolve("$targetName.yaml")

        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        println("✅ Installed skill '${skill.name}' to $target")
        return 0
    }

    private fun installFromMarketplace(name: String, skillsDir: Path): Int {
        val downloadUrl = "https://raw.githubusercontent.com/spola-skills/$name/main/SKILL.md"
        try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
            val request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 404) {
                System.err.println("Skill '$name' not found in marketplace (github.com/spola-skills/$name)")
                return 1
            }
            if (response.statusCode() !in 200..299) {
                System.err.println("Failed to download skill '$name': HTTP ${response.statusCode()}")
                return 1
            }

            // Parse frontmatter to determine category
            val content = response.body()
            val category = content.lines()
                .drop(1).dropWhile { !it.startsWith("---") }
                .firstOrNull { it.startsWith("category:") }
                ?.substringAfter("category:")
                ?.trim()
                ?.removeSurrounding("\"")
                ?: ""

            val targetDir = if (category.isNotBlank()) {
                skillsDir.resolve(category).resolve(name)
            } else {
                skillsDir.resolve(name)
            }
            Files.createDirectories(targetDir)
            val targetFile = targetDir.resolve("SKILL.md")
            Files.writeString(targetFile, content)
            println("✅ Installed skill '$name' from marketplace to $targetFile")
            return 0
        } catch (e: HttpTimeoutException) {
            System.err.println("Timeout downloading skill '$name' from marketplace")
            return 1
        } catch (e: Exception) {
            System.err.println("Failed to install skill '$name': ${e.message}")
            return 1
        }
    }
}

@Command(name = "search", description = ["Search the Spola skill marketplace on GitHub"])
class SkillSearchCommand : Callable<Int> {
    @ParentCommand
    lateinit var skillCommand: SkillCommand

    @Parameters(index = "0", description = ["Search query"])
    lateinit var query: String

    override fun call(): Int {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.github.com/search/repositories?q=${encodedQuery}+org:spola-skills&per_page=20"
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() !in 200..299) {
                System.err.println("Failed to search marketplace: HTTP ${response.statusCode()}")
                return 1
            }

            val body = response.body()
            val totalItems = extractJsonInt(body, "total_count")
            if (totalItems == 0) {
                println("No skills found matching '$query' in the marketplace.")
                return 0
            }

            println("Found $totalItems skill(s) matching '$query':")
            println("─".repeat(60))
            val items = extractItems(body)
            for (item in items) {
                val name = extractJsonString(item, "name") ?: "?"
                val description = extractJsonString(item, "description") ?: ""
                val stars = extractJsonInt(item, "stargazers_count")
                println("• $name — ${description.take(80)}${if (description.length > 80) "…" else ""} (★ $stars)")
            }
            println()
            println("Install with: spola skill install <name>")
            return 0
        } catch (e: Exception) {
            System.err.println("Failed to search marketplace: ${e.message}")
            return 1
        }
    }

    private fun extractItems(json: String): List<String> {
        val itemsKey = "\"items\":"
        val itemsStart = json.indexOf(itemsKey)
        if (itemsStart < 0) return emptyList()
        val start = itemsStart + itemsKey.length
        // Find matching closing bracket for the array
        var depth = 0
        var arrayStart = -1
        for (i in start until json.length) {
            when (json[i]) {
                '[' -> {
                    if (depth == 0) arrayStart = i
                    depth++
                }
                ']' -> {
                    depth--
                    if (depth == 0 && arrayStart >= 0) {
                        return extractJsonObjects(json.substring(arrayStart, i + 1))
                    }
                }
            }
        }
        return emptyList()
    }

    private fun extractJsonObjects(arrayJson: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var objStart = -1
        for (i in arrayJson.indices) {
            when (arrayJson[i]) {
                '{' -> {
                    if (depth == 0) objStart = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        objects.add(arrayJson.substring(objStart, i + 1))
                        objStart = -1
                    }
                }
            }
        }
        return objects
    }

    private fun extractJsonString(json: String, key: String): String? {
        val search = "\"$key\":"
        val idx = json.indexOf(search)
        if (idx < 0) return null
        val valueStart = idx + search.length
        val trimmed = json.substring(valueStart).trimStart()
        if (trimmed.startsWith("\"")) {
            // String value
            val sb = StringBuilder()
            var i = 1
            while (i < trimmed.length) {
                val c = trimmed[i]
                if (c == '\\') {
                    i++
                    if (i < trimmed.length) sb.append(trimmed[i])
                } else if (c == '"') {
                    break
                } else {
                    sb.append(c)
                }
                i++
            }
            return sb.toString()
        }
        // Number or other value — take up to comma or closing brace
        val end = trimmed.indexOfFirst { it == ',' || it == '}' || it == ']' }
        return if (end < 0) trimmed.trim() else trimmed.substring(0, end).trim().removeSurrounding("\"")
    }

    private fun extractJsonInt(json: String, key: String): Int {
        val search = "\"$key\":"
        val idx = json.indexOf(search)
        if (idx < 0) return 0
        val start = idx + search.length
        val end = json.indexOfAny(charArrayOf(',', '}'), start)
        return if (end < 0) 0 else json.substring(start, end).trim().toIntOrNull() ?: 0
    }
}

@Command(name = "publish", description = ["Publish a local skill to the marketplace (creates a GitHub issue)"])
class SkillPublishCommand : Callable<Int> {
    @ParentCommand
    lateinit var skillCommand: SkillCommand

    @Parameters(index = "0", description = ["Path to SKILL.md file or skill directory"])
    lateinit var path: String

    override fun call(): Int {
        val skillPath = Path.of(path)

        // Resolve SKILL.md if a directory was given
        val skillFile = if (Files.isDirectory(skillPath)) {
            val md = skillPath.resolve("SKILL.md")
            if (Files.exists(md)) md else {
                System.err.println("No SKILL.md found in directory: $skillPath")
                return 1
            }
        } else {
            skillPath
        }

        if (!Files.exists(skillFile)) {
            System.err.println("File not found: $skillFile")
            return 1
        }

        if (skillFile.fileName.toString() != "SKILL.md") {
            // Try loading as YAML skill first
            val skill = SkillLoader.loadFromFile(skillFile)
            if (skill == null) {
                System.err.println("Unsupported skill file format. Expected SKILL.md or a valid .yaml/.yml skill file.")
                System.err.println("Marketplace publishes SKILL.md files. Consider converting your YAML skill to SKILL.md format.")
                return 1
            }
            println("✅ Valid skill detected: '${skill.name}'")
        } else {
            // Load SKILL.md via SkillLoader to validate
            val skill = SkillLoader.loadFromFile(skillFile)
            if (skill == null) {
                System.err.println("Invalid SKILL.md file: $skillFile")
                return 1
            }
            println("✅ Valid skill detected: '${skill.name}'")
        }

        println()
        println("To publish this skill to the Spola marketplace:")
        println()
        println("  1. Fork the marketplace repository:")
        println("     https://github.com/spola-skills/marketplace")
        println()
        println("  2. Create a new directory for your skill:")
        println("     spola-skills/<your-skill-name>/")
        println()
        println("  3. Add your SKILL.md file to that directory")
        println()
        println("  4. Submit a Pull Request with your skill")
        println()
        println("Alternatively, create a new repository under the spola-skills")
        println("GitHub organization with your skill name:")
        println()
        println("  1. Create github.com/spola-skills/<your-skill-name>")
        println("  2. Add SKILL.md to the main branch")
        println("  3. It will be discoverable via 'spola skill search'")
        println()
        println("Contact the Spola team for access to the spola-skills organization.")
        return 0
    }
}

/**
 * Resolve the skills directory from CLI options or default.
 */
internal fun skillsDirectory(root: SpolaCli): Path {
    val configured = buildConfig(root).skillsDir
    return if (configured.isBlank()) {
        Path.of(System.getProperty("user.home"), ".spola", "skills")
    } else {
        Path.of(configured)
    }
}
