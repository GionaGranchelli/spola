package dev.spola.workflow.yaml

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parses YAML workflow definition files into [WorkflowDefinition] data classes.
 * Uses the same Jackson YAML mapper that SpolaConfigFileStore uses.
 */
object YamlWorkflowParser {

    private val logger = LoggerFactory.getLogger(YamlWorkflowParser::class.java)

    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /**
     * Parse a YAML file into a [WorkflowDefinition].
     * Returns null if parsing fails (malformed YAML, missing required fields).
     */
    fun parseFile(path: Path): WorkflowDefinition? {
        return try {
            if (!Files.isRegularFile(path)) {
                logger.warn("Workflow file not found: {}", path)
                return null
            }
            val content = Files.readString(path)
            parseContent(content, path)
        } catch (e: Exception) {
            logger.warn("Failed to read workflow file {}: {}", path, e.message)
            null
        }
    }

    /**
     * Parse YAML string content into a [WorkflowDefinition].
     */
    fun parseContent(content: String, source: Path? = null): WorkflowDefinition? {
        return try {
            val raw: Map<String, Any?> = mapper.readValue(content, object : TypeReference<Map<String, Any?>>() {})

            val name = (raw["name"] as? String)?.trim()
                ?: return errorOrNull(source, "Missing required field 'name'")

            val stepsRaw = raw["steps"] as? List<*>
                ?: return errorOrNull(source, "Missing required field 'steps'")

            val steps = stepsRaw.mapNotNull { stepRaw ->
                mapper.convertValue(normalizeStepRaw(stepRaw), StepDef::class.java)
            }

            if (steps.isEmpty()) {
                return errorOrNull(source, "At least one step is required")
            }

            val paramsRaw = raw["params"] as? Map<*, *>
            val params = paramsRaw?.let { parseParams(it) } ?: emptyMap()

            val doneRaw = raw["done"] as? List<*>
            val done = doneRaw?.mapNotNull { doneItem ->
                when (doneItem) {
                    is String -> DoneCondition(condition = doneItem)
                    is Map<*, *> -> mapper.convertValue(doneItem, DoneCondition::class.java)
                    else -> null
                }
            } ?: emptyList()

            WorkflowDefinition(
                name = name,
                version = (raw["version"] as? String)?.trim() ?: "1",
                description = (raw["description"] as? String)?.trim() ?: "",
                params = params,
                steps = steps,
                done = done,
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse workflow YAML{}: {}",
                source?.let { " from $it" } ?: "", e.message)
            null
        }
    }

    private fun parseParams(raw: Map<*, *>): Map<String, ParamDef> {
        return raw.entries.mapNotNull { entry ->
            val key = entry.key?.toString() ?: return@mapNotNull null
            val value = entry.value
            val paramDef = when (value) {
                is Map<*, *> -> mapper.convertValue(value, ParamDef::class.java)
                is String -> ParamDef(type = "string", default = value)
                is Number -> ParamDef(type = "number", default = value)
                is Boolean -> ParamDef(type = "boolean", default = value)
                is List<*> -> ParamDef(type = "array", default = value)
                else -> ParamDef()
            }
            key to paramDef
        }.toMap()
    }

    private fun normalizeStepRaw(stepRaw: Any?): Any? {
        val rawMap = stepRaw as? Map<*, *> ?: return stepRaw
        val normalized = rawMap.entries
            .mapNotNull { (key, value) -> key?.toString()?.let { it to value } }
            .toMap(mutableMapOf())

        if ("depends_on" !in normalized && "dependsOn" in normalized) {
            normalized["depends_on"] = normalized["dependsOn"]
        }
        if ("workflow_ref" !in normalized && "workflowRef" in normalized) {
            normalized["workflow_ref"] = normalized["workflowRef"]
        }
        if ("on_error" !in normalized && "onError" in normalized) {
            normalized["on_error"] = normalized["onError"]
        }
        if ("retry_count" !in normalized && "retryCount" in normalized) {
            normalized["retry_count"] = normalized["retryCount"]
        }
        if ("max_output_bytes" !in normalized && "maxOutputBytes" in normalized) {
            normalized["max_output_bytes"] = normalized["maxOutputBytes"]
        }

        return normalized
    }

    private fun errorOrNull(source: Path?, message: String): Nothing? {
        logger.warn("Invalid workflow definition{}: {}", source?.let { " ($it)" } ?: "", message)
        return null
    }
}
