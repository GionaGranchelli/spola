package dev.spola.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * Loads AgentDefinitions from YAML files in a directory
 * (default: ~/.golem/agents/).
 *
 * Each .yaml or .yml file in the directory becomes one agent.
 * The filename (without extension) is used as the agent id
 * if not specified in the YAML frontmatter.
 */
object AgentLoader {

    private val mapper = ObjectMapper(YAMLFactory())

    /** Default directory for YAML agent definitions. */
    val defaultAgentsDir: Path = Paths.get(
        System.getProperty("user.home"), ".golem", "agents"
    )

    /**
     * Load all agents from the [directory].
     * Returns a list of loaded definitions.
     * Skips files that fail to parse (logs warnings).
     */
    fun loadFromDirectory(directory: Path = defaultAgentsDir): List<AgentDefinition> {
        if (!Files.exists(directory)) {
            return emptyList()
        }

        return Files.list(directory).use { stream ->
            stream.toList()
                .filter { p -> Files.isRegularFile(p) && (p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")) }
                .mapNotNull { p -> loadFromFile(p) }
        }
    }

    /**
     * Load a single agent from a YAML file.
     * Uses the filename stem as agent id if `id` field is missing.
     */
    fun loadFromFile(filePath: Path): AgentDefinition? {
        return try {
            val content = Files.readString(filePath)
            val agent = mapper.readValue<AgentDefinition>(content)

            val stem = filePath.fileName.toString().let { name ->
                name.removeSuffix(".yaml").removeSuffix(".yml")
            }

            val now = Instant.now().toString()

            agent.copy(
                id = agent.id.ifBlank { stem },
                createdAt = agent.createdAt.ifBlank { now },
                updatedAt = agent.updatedAt.ifBlank { now },
            )
        } catch (e: Exception) {
            System.err.println("[AgentLoader] Failed to load ${filePath}: ${e.message}")
            null
        }
    }

    /**
     * Write an AgentDefinition to a YAML file in the given directory.
     */
    fun writeToFile(directory: Path, agent: AgentDefinition) {
        Files.createDirectories(directory)
        val filePath = directory.resolve("${agent.id}.yaml")
        val yaml = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(agent)
        Files.writeString(filePath, yaml)
    }

    /**
     * Delete the YAML file for a given agent id.
     */
    fun deleteFile(directory: Path, agentId: String): Boolean {
        val filePath = directory.resolve("$agentId.yaml")
        return Files.deleteIfExists(filePath)
    }

    /**
     * Sync YAML files from [directory] into [store].
     * Adds new agents, updates existing ones (by id), removes orphans.
     * Returns the list of all known agents after sync.
     */
    suspend fun sync(
        store: AgentStore,
        directory: Path = defaultAgentsDir,
    ): List<AgentDefinition> = kotlinx.coroutines.runBlocking {
        val fileAgents = loadFromDirectory(directory)
        val storedAgents = store.list()
        val storedIds = storedAgents.map { it.id }.toSet()

        // Add or update from files
        for (agent in fileAgents) {
            if (agent.id in storedIds) {
                store.update(agent.copy(updatedAt = Instant.now().toString()))
            } else {
                store.create(agent)
            }
        }

        // Remove orphans (agents in store but not in files)
        val fileIds = fileAgents.map { it.id }.toSet()
        for (stored in storedAgents) {
            if (stored.id !in fileIds) {
                store.delete(stored.id)
            }
        }

        store.list()
    }

    /** Detect if the Jackson YAML module is on the classpath. */
    val isAvailable: Boolean by lazy {
        try {
            Class.forName("com.fasterxml.jackson.dataformat.yaml.YAMLFactory")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}
