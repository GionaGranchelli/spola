package dev.spola

import dev.spola.checkpoint.CheckpointManager
import dev.spola.metrics.GolemMetrics
import dev.spola.skill.SkillToolDef
import dev.spola.skill.SkillToolParam
import dev.spola.tools.registerProvenanceTools
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * A tool that the agent can call. Tools are registered in a [ToolRegistry].
 */
data class Tool(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
    val execute: suspend (Map<String, Any>) -> ToolResult,
)

/**
 * A parameter for a tool, used to generate JSON schema for the LLM.
 */
data class ToolParameter(
    val name: String,
    val description: String,
    val type: ToolParameterType,
    val required: Boolean = true,
    val defaultValue: Any? = null,
)

enum class ToolParameterType {
    STRING,
    INTEGER,
    BOOLEAN,
}

/**
 * Result of a tool execution.
 */
data class ToolResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
) {
    companion object {
        fun ok(output: String) = ToolResult(success = true, output = output)
        fun fail(error: String) = ToolResult(success = false, output = error, error = error)
    }
}

/**
 * Registry of all available tools.
 */
class ToolRegistry {
    private val logger = LoggerFactory.getLogger(ToolRegistry::class.java)
    private val tools = ConcurrentHashMap<String, Tool>()
    private val enabledTools = ConcurrentHashMap.newKeySet<String>()
    private val skillTools = ConcurrentHashMap<String, List<String>>()

    fun register(tool: Tool) {
        if (tools.containsKey(tool.name)) {
            logger.warn("Tool name conflict detected for '{}'; replacing existing tool", tool.name)
        }
        tools[tool.name] = tool
        enabledTools.add(tool.name)
    }

    fun get(name: String): Tool? = tools[name]

    fun list(): Collection<Tool> = tools.values.filter { enabledTools.contains(it.name) }

    fun listAll(): Collection<Tool> = tools.values

    fun isEnabled(name: String): Boolean = enabledTools.contains(name)

    fun listEnabled(): List<Tool> = tools.values.filter { enabledTools.contains(it.name) }

    fun toggleEnabled(name: String): Boolean {
        val tool = tools[name] ?: return false
        return if (enabledTools.contains(name)) {
            enabledTools.remove(name)
            false
        } else {
            enabledTools.add(name)
            true
        }
    }

    /** Remove a tool from the registry entirely. */
    fun unregister(name: String): Boolean {
        enabledTools.remove(name)
        return tools.remove(name) != null
    }

    fun rebuildModelDependentTools(newModel: String, manager: CheckpointManager, metrics: GolemMetrics?) {
        unregister("provenance_export")
        unregister("provenance_list")
        unregister("provenance_info")
        registerProvenanceTools(this, manager, metrics, model = newModel)
    }

    fun activateSkill(skillName: String, tools: List<SkillToolDef>, body: String = ""): List<String> {
        val normalizedSkillName = skillName.lowercase()
        deactivateSkill(normalizedSkillName)

        val registered = mutableListOf<String>()
        for (toolDef in tools) {
            val toolName = "$normalizedSkillName.${toolDef.name}"
            val tool = Tool(
                name = toolName,
                description = toolDef.description,
                parameters = toolDef.parameters.map { it.toToolParameter() },
                execute = { args ->
                    val argsSummary = if (args.isNotEmpty()) {
                        args.entries.joinToString(", ") { "${it.key}=${it.value}" }
                    } else null
                    ToolResult.ok(
                        buildString {
                            append("## Skill Tool: $toolName\n")
                            append("Skill: $skillName\n")
                            append("Description: ${toolDef.description}\n")
                            if (argsSummary != null) {
                                append("Arguments received: $argsSummary\n")
                            }
                            if (body.isNotBlank()) {
                                append("\n")
                                append(body)
                            } else {
                                append("\nThis skill has no body content.\n")
                            }
                        },
                    )
                },
            )
            register(tool)
            registered.add(toolName)
        }
        skillTools[normalizedSkillName] = registered
        return registered
    }

    fun deactivateSkill(skillName: String): Boolean {
        val names = skillTools.remove(skillName.lowercase()) ?: return false
        names.forEach { unregister(it) }
        return true
    }

    fun schemas(): List<Map<String, Any?>> = tools.values.filter { enabledTools.contains(it.name) }.map { tool ->
        mapOf(
            "name" to tool.name,
            "description" to tool.description,
            "parameters" to mapOf(
                "type" to "object",
                "properties" to tool.parameters.associate { param ->
                    param.name to buildJsonParamSchema(param)
                },
                "required" to tool.parameters.filter { it.required }.map { it.name },
            ),
        )
    }

    private fun buildJsonParamSchema(param: ToolParameter): Map<String, Any?> {
        val schema = mutableMapOf<String, Any?>(
            "type" to when (param.type) {
                ToolParameterType.STRING -> "string"
                ToolParameterType.INTEGER -> "integer"
                ToolParameterType.BOOLEAN -> "boolean"
            },
            "description" to param.description,
        )
        if (param.defaultValue != null) {
            schema["default"] = param.defaultValue
        }
        return schema
    }
}

private fun SkillToolParam.toToolParameter(): ToolParameter = ToolParameter(
    name = name,
    description = description,
    type = when (type.lowercase()) {
        "integer" -> ToolParameterType.INTEGER
        "boolean" -> ToolParameterType.BOOLEAN
        else -> ToolParameterType.STRING
    },
    required = required,
    defaultValue = defaultValue,
)
