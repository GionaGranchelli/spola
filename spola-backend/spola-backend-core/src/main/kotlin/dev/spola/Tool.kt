package dev.spola

import dev.spola.checkpoint.CheckpointManager
import dev.spola.metrics.SpolaMetrics
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
    val enumValues: List<String> = emptyList(),
)

enum class ToolParameterType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    ARRAY,
    OBJECT,
    ENUM,
}

internal fun ToolParameterType.toTypeString(): String = when (this) {
    ToolParameterType.STRING -> "string"
    ToolParameterType.INTEGER -> "integer"
    ToolParameterType.NUMBER -> "number"
    ToolParameterType.BOOLEAN -> "boolean"
    ToolParameterType.ARRAY -> "array"
    ToolParameterType.OBJECT -> "object"
    ToolParameterType.ENUM -> "enum"
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

    fun get(name: ToolName): Tool? = tools[name.value]

    fun get(name: String): Tool? = get(ToolName(name))

    fun list(): Collection<Tool> = tools.values.filter { enabledTools.contains(it.name) }

    fun listAll(): Collection<Tool> = tools.values

    fun isEnabled(name: ToolName): Boolean = enabledTools.contains(name.value)

    fun isEnabled(name: String): Boolean = isEnabled(ToolName(name))

    fun listEnabled(): List<Tool> = tools.values.filter { enabledTools.contains(it.name) }

    fun toggleEnabled(name: ToolName): Boolean {
        val tool = tools[name.value] ?: return false
        return if (enabledTools.contains(name.value)) {
            enabledTools.remove(name.value)
            false
        } else {
            enabledTools.add(name.value)
            true
        }
    }

    fun toggleEnabled(name: String): Boolean = toggleEnabled(ToolName(name))

    /** Remove a tool from the registry entirely. */
    fun unregister(name: ToolName): Boolean {
        enabledTools.remove(name.value)
        return tools.remove(name.value) != null
    }

    fun unregister(name: String): Boolean = unregister(ToolName(name))

    fun rebuildModelDependentTools(newModel: ModelName, manager: CheckpointManager, metrics: SpolaMetrics?) {
        unregister(ToolName("provenance_export"))
        unregister(ToolName("provenance_list"))
        unregister(ToolName("provenance_info"))
        registerProvenanceTools(this, manager, metrics, model = newModel.value)
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
        names.forEach { unregister(ToolName(it)) }
        return true
    }

    fun schemas(): List<Map<String, Any?>> = tools.values.filter { enabledTools.contains(it.name) }.map { tool ->
        mapOf(
            "name" to tool.name,
            "description" to tool.description,
            "parameters" to mapOf(
                "type" to "object",
                "properties" to tool.parameters.associate { param ->
                    param.name to param.toJsonSchema()
                },
                "required" to tool.parameters.filter { it.required }.map { it.name },
            ),
        )
    }
}

fun ToolParameter.toJsonSchema(): Map<String, Any?> {
    val schema = mutableMapOf<String, Any?>(
        "description" to description,
    )

    when (type) {
        ToolParameterType.ARRAY -> {
            schema["type"] = "array"
            schema["items"] = emptyMap<String, Any?>()
        }
        ToolParameterType.OBJECT -> {
            schema["type"] = "object"
            schema["additionalProperties"] = true
        }
        ToolParameterType.ENUM -> {
            schema["type"] = "string"
            if (enumValues.isNotEmpty()) {
                schema["enum"] = enumValues
            }
        }
        else -> schema["type"] = type.toTypeString()
    }

    if (defaultValue != null) {
        schema["default"] = defaultValue
    }
    return schema
}

private fun SkillToolParam.toToolParameter(): ToolParameter = ToolParameter(
    name = name,
    description = description,
    type = when (type.lowercase()) {
        "integer" -> ToolParameterType.INTEGER
        "number" -> ToolParameterType.NUMBER
        "boolean" -> ToolParameterType.BOOLEAN
        "array" -> ToolParameterType.ARRAY
        "object" -> ToolParameterType.OBJECT
        "enum" -> ToolParameterType.ENUM
        else -> ToolParameterType.STRING
    },
    required = required,
    defaultValue = defaultValue,
)
