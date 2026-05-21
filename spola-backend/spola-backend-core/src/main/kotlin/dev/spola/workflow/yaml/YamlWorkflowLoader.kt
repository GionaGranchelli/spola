package dev.spola.workflow.yaml

import dev.spola.SpolaConfig
import dev.spola.workflow.SpolaState
import dev.spola.workflow.WorkflowTemplate
import dev.spola.workflow.WorkflowTemplateRegistry
import dev.tramai.orchestration.Workflow
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

object YamlWorkflowLoader {

    private val logger = LoggerFactory.getLogger(YamlWorkflowLoader::class.java)

    val defaultWorkflowsDir: Path = Path.of(
        System.getProperty("user.home"), ".spola", "workflows"
    )

    fun loadAndRegister(
        registry: WorkflowTemplateRegistry,
        config: SpolaConfig,
        customDir: Path? = null,
    ) {
        val dir = customDir ?: defaultWorkflowsDir
        if (!Files.isDirectory(dir)) {
            logger.info("Workflows directory does not exist, skipping YAML workflow loading: {}", dir)
            return
        }

        val files = discoverWorkflowFiles(dir)
        if (files.isEmpty()) {
            logger.info("No YAML workflow files found in {}", dir)
            return
        }

        var registered = 0
        for (file in files) {
            val definition = YamlWorkflowParser.parseFile(file)
            if (definition != null) {
                val template = YamlWorkflowTemplate(definition, config, registry)
                try {
                    registry.register(template)
                    logger.info("Registered YAML workflow '{}' from {}", definition.name, file)
                    registered++
                } catch (e: Exception) {
                    logger.warn("Failed to register workflow '{}' from {}: {}",
                        definition.name, file, e.message)
                }
            }
        }

        logger.info("Loaded {} YAML workflows from {}", registered, dir)
    }

    fun discoverWorkflowFiles(dir: Path): List<Path> {
        return Files.list(dir)
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().endsWith(".yaml") || it.fileName.toString().endsWith(".yml") }
            .sorted()
            .toList()
    }
}

class YamlWorkflowTemplate(
    private val definition: WorkflowDefinition,
    private val config: SpolaConfig,
    private val registry: WorkflowTemplateRegistry,
) : WorkflowTemplate {

    override val name: String get() = definition.name
    override val version: String get() = definition.version
    override val supportsRecursiveCompilation: Boolean = true

    override fun compileRecursive(
        config: SpolaConfig,
        goal: String,
        registry: WorkflowTemplateRegistry,
        parentsChain: Set<String>,
    ): Workflow<SpolaState, String> {
        val resolved = WorkflowParameterResolver.resolve(
            definition = definition,
            runtimeParams = emptyMap(),
            goal = goal,
        )
        return YamlWorkflowCompiler.compile(
            resolved = resolved,
            config = config,
            goal = goal,
            registry = registry,
            parentsChain = parentsChain,
        )
    }

    override fun build(
        config: SpolaConfig,
        goal: String,
        parametersJson: String,
    ): Workflow<SpolaState, String> {
        val runtimeParams = parseParametersJson(parametersJson)
        val resolved = WorkflowParameterResolver.resolve(
            definition = definition,
            runtimeParams = runtimeParams,
            goal = goal,
        )
        return YamlWorkflowCompiler.compile(
            resolved = resolved,
            config = config,
            goal = goal,
            registry = registry,
        )
    }

    private fun parseParametersJson(json: String): Map<String, Any?> {
        if (json.isBlank() || json == "{}") {
            return emptyMap()
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            val parsed = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                .readValue(json, Map::class.java)
            parsed as? Map<String, Any?> ?: emptyMap()
        } catch (e: Exception) {
            logger.warn("Failed to parse parameters JSON '{}': {}", json, e.message)
            emptyMap()
        }
    }

    private val logger = LoggerFactory.getLogger(YamlWorkflowTemplate::class.java)
}
