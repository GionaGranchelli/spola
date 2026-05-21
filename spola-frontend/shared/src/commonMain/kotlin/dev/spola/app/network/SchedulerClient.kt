package dev.spola.app.network

import dev.spola.models.AgentRunResponse
import dev.spola.models.ScheduledJobResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class SchedulerClient(
    private val client: HttpClient,
    private val json: Json,
    private val runAgent: suspend (String, String?) -> AgentRunResponse,
) {
    suspend fun getScheduledJobs(): List<ScheduledJobResponse> {
        val endpointCandidates = listOf(
            "api/scheduler",
            "api/scheduler/jobs",
            "api/schedules",
        )

        endpointCandidates.forEach { path ->
            val jobs = runCatching {
                val payload = client.get(path).bodyAsText()
                parseScheduledJobs(payload)
            }.getOrNull()
            if (jobs != null) {
                return jobs
            }
        }

        val fallback = runAgent(
            """
                Use the scheduler_list tool and return only JSON in the form
                {"jobs":[{"id":"","name":"","goal":"","cronExpression":"","enabled":true,"createdAt":0,"nextRunAt":0}]}
            """.trimIndent(),
            null,
        )
        return parseScheduledJobs(fallback.result)
    }

    private fun parseScheduledJobs(rawPayload: String): List<ScheduledJobResponse> {
        val jsonText = extractJsonBlock(rawPayload)
        val payload = json.parseToJsonElement(jsonText)
        val jobs = when (payload) {
            is JsonArray -> payload
            is JsonObject -> payload["jobs"] as? JsonArray
                ?: payload["schedules"] as? JsonArray
                ?: payload["entries"] as? JsonArray
                ?: error("No scheduler jobs array found in response")
            else -> error("Unsupported scheduler response")
        }

        return jobs.map { jobElement ->
            val job = jobElement.jsonObject
            ScheduledJobResponse(
                id = job.stringValue("id"),
                name = job.stringValue("name"),
                goal = job.stringValue("goal"),
                cronExpression = job.stringValue("cronExpression", "schedule", "cron"),
                enabled = job.booleanValue("enabled"),
                createdAt = job.longValue("createdAt"),
                nextRunAt = job.longValue("nextRunAt"),
            )
        }
    }

    private fun extractJsonBlock(input: String): String {
        val trimmed = input.trim()
        val withoutFence = trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val objectStart = withoutFence.indexOf('{')
        val arrayStart = withoutFence.indexOf('[')
        val start = listOf(objectStart, arrayStart).filter { it >= 0 }.minOrNull()
            ?: error("No JSON payload found")
        val end = maxOf(withoutFence.lastIndexOf('}'), withoutFence.lastIndexOf(']'))
        if (end <= start) error("No complete JSON payload found")
        return withoutFence.substring(start, end + 1)
    }

    private fun JsonObject.stringValue(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> this[key]?.jsonPrimitive?.contentOrNull }.orEmpty()

    private fun JsonObject.booleanValue(key: String): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: false

    private fun JsonObject.longValue(key: String): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: 0L
}
