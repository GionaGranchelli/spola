package dev.spola.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.spola.GolemConfig
import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import dev.spola.workflow.yaml.WorkflowDefinition
import dev.spola.workflow.yaml.YamlWorkflowParser
import dev.spola.workflow.yaml.YamlWorkflowTemplate
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Registers LLM-accessible tools for creating and deleting YAML workflow definitions.
 */
object WorkflowCreateTools {

    private val logger = LoggerFactory.getLogger(WorkflowCreateTools::class.java)

    private val yamlMapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

    suspend fun register(
        registry: ToolRegistry,
        config: GolemConfig,
        workflowRegistry: WorkflowTemplateRegistry,
    ) {
        registry.register(workflowCreateTool(config, workflowRegistry))
        registry.register(workflowDeleteTool(config, workflowRegistry))
    }

    private fun workflowsDir(config: GolemConfig): Path = Path.of(config.workflowsDir)

    private fun workflowCreateTool(
        config: GolemConfig,
        workflowRegistry: WorkflowTemplateRegistry,
    ) = Tool(
        name = "workflow_create",
        description = "Create a new YAML workflow definition file. Takes a workflow name, a JSON array of step objects, and optional description/params/done. Validates the YAML by parsing and registering. Returns the file path.",
        parameters = listOf(
            ToolParameter(
                name = "name",
                description = "Unique workflow name (used as filename: <name>.yaml). Lowercase, hyphens allowed.",
                type = ToolParameterType.STRING,
            ),
            ToolParameter(
                name = "steps_json",
                description = "JSON array of step objects. Each step has: id (string, unique), type (string: ai|shell|local|human_approval|composite|parallel_agents), goal (string, for ai steps), command (string, for shell/local steps), depends_on (array of step ids, optional), prompt (string, for human_approval or composite, optional), workflow_ref (string, for composite steps), persona (string, optional), agents (array of strings, for parallel_agents), timeout (int, default 60), on_error (string: fail|continue, default fail), retry_count (int, default 0), done (array of objects: {condition, value}, optional), env (object, optional), max_output_bytes (long, optional).",
                type = ToolParameterType.STRING,
            ),
            ToolParameter(
                name = "description",
                description = "Human-readable description of what this workflow does.",
                type = ToolParameterType.STRING,
                required = false,
            ),
            ToolParameter(
                name = "params_json",
                description = "Optional JSON object of parameter definitions. Each key is a param name, value is an object with optional fields: type (string, default 'string'), description (string), required (boolean), default (any).",
                type = ToolParameterType.STRING,
                required = false,
            ),
            ToolParameter(
                name = "done_json",
                description = "Optional JSON array of workflow-level done condition objects: [{condition: string, value: string}]. E.g. [{\"condition\":\"all_steps_passed\"}, {\"condition\":\"output_contains\",\"value\":\"BUILD OK\"}].",
                type = ToolParameterType.STRING,
                required = false,
            ),
        ),
        execute = { args ->
            val name = args["name"] as? String ?: return@Tool ToolResult.fail("name is required")
            val stepsJson = args["steps_json"] as? String ?: return@Tool ToolResult.fail("steps_json is required")

            if (!name.matches(Regex("^[a-z][a-z0-9_-]*$"))) {
                return@Tool ToolResult.fail("Invalid workflow name '$name'. Use lowercase letters, digits, hyphens, underscores, starting with a letter.")
            }

            val dir = workflowsDir(config)
            Files.createDirectories(dir)

            val file = dir.resolve("$name.yaml")
            if (Files.exists(file)) {
                return@Tool ToolResult.fail("Workflow '$name' already exists at $file. Use workflow_delete first or choose a different name.")
            }

            try {
                // Parse steps from JSON
                val jsonMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                val steps: List<Map<String, Any?>> = jsonMapper.readValue(stepsJson)

                // Build YAML string
                val yaml = buildYaml(name, steps, args, jsonMapper)

                // Validate — parse and compile the YAML
                Files.writeString(file, yaml)
                val parsed = YamlWorkflowParser.parseFile(file)
                if (parsed == null) {
                    Files.deleteIfExists(file)
                    return@Tool ToolResult.fail("Created YAML is invalid — parser returned null. File has been deleted.")
                }

                // Register
                val template = YamlWorkflowTemplate(parsed, config, workflowRegistry)
                try {
                    workflowRegistry.register(template)
                } catch (e: Exception) {
                    Files.deleteIfExists(file)
                    return@Tool ToolResult.fail("Failed to register workflow '${parsed.name}': ${e.message}")
                }

                logger.info("Created YAML workflow '{}' at {}", name, file)
                ToolResult.ok("Workflow '$name' created and registered at $file")
            } catch (e: Exception) {
                Files.deleteIfExists(file)
                ToolResult.fail("Failed to create workflow: ${e.message}")
            }
        },
    )

    private fun buildYaml(
        name: String,
        steps: List<Map<String, Any?>>,
        args: Map<String, Any?>,
        jsonMapper: com.fasterxml.jackson.databind.ObjectMapper,
    ): String {
        val description = args["description"] as? String ?: ""
        val paramsJson = args["params_json"] as? String
        val doneJson = args["done_json"] as? String

        val sb = StringBuilder()
        sb.appendLine("name: $name")
        sb.appendLine("version: \"1\"")
        if (description.isNotBlank()) {
            sb.appendLine("description: \"${escapeYaml(description)}\"")
        }

        // Params
        if (paramsJson != null && paramsJson.isNotBlank()) {
            val params: Map<String, Any?> = jsonMapper.readValue(paramsJson)
            if (params.isNotEmpty()) {
                sb.appendLine("params:")
                for ((key, value) in params) {
                    sb.appendLine("  $key:")
                    if (value is Map<*, *>) {
                        for ((vk, vv) in value) {
                            sb.appendLine("    $vk: ${formatYamlValue(vv)}")
                        }
                    }
                }
            }
        }

        // Steps
        sb.appendLine("steps:")
        for (step in steps) {
            sb.appendLine("  - id: ${step["id"]}")
            sb.appendLine("    type: ${step["type"]}")
            for ((key, value) in step) {
                when (key) {
                    "id", "type" -> continue
                    "goal" -> if (value is String && value.isNotBlank()) sb.appendLine("    goal: \"${escapeYaml(value)}\"")
                    "command" -> if (value is String && value.isNotBlank()) sb.appendLine("    command: \"${escapeYaml(value)}\"")
                    "prompt" -> if (value is String && value.isNotBlank()) sb.appendLine("    prompt: \"${escapeYaml(value)}\"")
                    "persona" -> if (value is String && value.isNotBlank()) sb.appendLine("    persona: \"${escapeYaml(value)}\"")
                    "workflow_ref" -> if (value is String && value.isNotBlank()) sb.appendLine("    workflow_ref: $value")
                    "timeout" -> if (value is Number) sb.appendLine("    timeout: ${value.toInt()}")
                    "retry_count" -> if (value is Number) sb.appendLine("    retry_count: ${value.toInt()}")
                    "on_error" -> if (value is String) sb.appendLine("    on_error: $value")
                    "max_output_bytes" -> if (value is Number) sb.appendLine("    max_output_bytes: ${value.toLong()}")
                    "depends_on" -> if (value is List<*>) sb.appendLine("    depends_on: [${(value as List<String>).joinToString(", ")}]")
                    "agents" -> if (value is List<*>) sb.appendLine("    agents: [${(value as List<String>).joinToString(", ")}]")
                    "done" -> if (value is List<*>) {
                        val conditions = value as List<Map<String, Any?>>
                        sb.appendLine("    done:")
                        for (cond in conditions) {
                            val condName = cond["condition"] ?: continue
                            val condValue = cond["value"]
                            if (condValue != null) {
                                sb.appendLine("      - condition: $condName")
                                sb.appendLine("        value: \"${escapeYaml(condValue.toString())}\"")
                            } else {
                                sb.appendLine("      - condition: $condName")
                            }
                        }
                    }
                    "env" -> if (value is Map<*, *>) {
                        sb.appendLine("    env:")
                        for ((ek, ev) in value) {
                            sb.appendLine("      ${ek}: \"${escapeYaml(ev?.toString() ?: "")}\"")
                        }
                    }
                }
            }
        }

        // Workflow-level done
        if (doneJson != null && doneJson.isNotBlank()) {
            val done: List<Map<String, Any?>> = jsonMapper.readValue(doneJson)
            if (done.isNotEmpty()) {
                sb.appendLine("done:")
                for (cond in done) {
                    val condName = cond["condition"] ?: continue
                    val condValue = cond["value"]
                    if (condValue != null) {
                        sb.appendLine("  - condition: $condName")
                        sb.appendLine("    value: \"${escapeYaml(condValue.toString())}\"")
                    } else {
                        sb.appendLine("  - condition: $condName")
                    }
                }
            }
        }

        return sb.toString()
    }

    private fun workflowDeleteTool(
        config: GolemConfig,
        workflowRegistry: WorkflowTemplateRegistry,
    ) = Tool(
        name = "workflow_delete",
        description = "Delete a YAML workflow definition file by name. Unregisters it from the workflow registry and removes the file. Use this when a workflow is no longer needed or before recreating it.",
        parameters = listOf(
            ToolParameter(
                name = "name",
                description = "Name of the workflow to delete.",
                type = ToolParameterType.STRING,
            ),
        ),
        execute = { args ->
            val name = args["name"] as? String ?: return@Tool ToolResult.fail("name is required")

            val file = workflowsDir(config).resolve("$name.yaml")
            if (!Files.exists(file)) {
                return@Tool ToolResult.fail("Workflow file not found: $file")
            }

            try {
                Files.deleteIfExists(file)
                logger.info("Deleted workflow file: {}", file)
                ToolResult.ok("Workflow '$name' deleted. The file will be removed from the registry on next restart or explicit reload.")
            } catch (e: Exception) {
                ToolResult.fail("Failed to delete workflow '$name': ${e.message}")
            }
        },
    )

    private fun escapeYaml(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\t", "\\t")

    private fun formatYamlValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${escapeYaml(value)}\""
        is Boolean -> value.toString()
        is Number -> value.toString()
        else -> "\"${escapeYaml(value.toString())}\""
    }
}
