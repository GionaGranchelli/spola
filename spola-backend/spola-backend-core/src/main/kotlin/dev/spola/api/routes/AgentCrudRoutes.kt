package dev.spola.api

import dev.spola.ValidationException
import dev.spola.SpolaConfig
import dev.spola.SpolaFactory
import dev.spola.api.AgentCreateRequest
import dev.spola.api.AgentUpdateRequest
import dev.spola.api.AgentRunAgentRequest
import dev.spola.agent.AgentDefinition
import dev.spola.agent.AgentStore
import dev.spola.agent.SqliteAgentStore
import dev.spola.agent.ToolPolicy
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.request.receive

fun Route.apiAgentCrudRoutes(
    config: SpolaConfig,
    providedAgentStore: AgentStore?,
) {
    val localAgentStore = providedAgentStore ?: SqliteAgentStore(config.database.agentsDbPath)

    get("/agents") {
        call.enforceBearerAuth(config.security.apiKey)
        val tag = call.request.queryParameters["tag"]?.trim().orEmpty()
        val agents = localAgentStore.list(tag.ifBlank { null })
        call.respond(agents.map { it.toResponse() })
    }

    get("/agents/{id}") {
        call.enforceBearerAuth(config.security.apiKey)
        val id = call.requirePathParameter("id", "agent id")
        val agent = localAgentStore.get(id).orNotFound { "agent not found: $id" }
        call.respond(agent.toResponse())
    }

    post("/agents") {
        call.enforceBearerAuth(config.security.apiKey)
        val request = call.receive<AgentCreateRequest>()
        val agent = AgentDefinition(
            id = request.id, name = request.name,
            description = request.description, systemPrompt = request.systemPrompt,
            preferredModel = request.preferredModel, preferredProvider = request.preferredProvider,
            fallbackModel = request.fallbackModel, fallbackProvider = request.fallbackProvider,
            temperature = request.temperature, maxTokens = request.maxTokens,
            toolPolicy = request.toolPolicy, toolsAllowed = request.toolsAllowed,
            filesystemAccess = request.filesystemAccess,
            shellAccess = request.shellAccess, networkAccess = request.networkAccess,
            executeCommands = request.executeCommands, memoryScope = request.memoryScope,
            memoryNamespace = if (request.memoryScope == "agent") request.id else null,
            tags = request.tags, responseFormat = request.responseFormat,
        )
        val created = localAgentStore.create(agent)
        call.respond(HttpStatusCode.Created, created.toResponse())
    }

    put("/agents/{id}") {
        call.enforceBearerAuth(config.security.apiKey)
        val id = call.requirePathParameter("id", "agent id")
        val existing = localAgentStore.get(id).orNotFound { "agent not found: $id" }
        val request = call.receive<AgentUpdateRequest>()
        val updatedToolPolicy = request.toolPolicy ?: existing.toolPolicy
        val updatedMemoryScope = request.memoryScope ?: existing.memoryScope
        val updated = existing.copy(
            name = request.name ?: existing.name,
            description = request.description ?: existing.description,
            systemPrompt = request.systemPrompt ?: existing.systemPrompt,
            preferredModel = request.preferredModel ?: existing.preferredModel,
            preferredProvider = request.preferredProvider ?: existing.preferredProvider,
            fallbackModel = request.fallbackModel ?: existing.fallbackModel,
            toolPolicy = updatedToolPolicy,
            toolsAllowed = when {
                request.toolsAllowed != null -> request.toolsAllowed
                updatedToolPolicy != ToolPolicy.LISTED -> emptyList()
                else -> existing.toolsAllowed
            },
            filesystemAccess = request.filesystemAccess ?: existing.filesystemAccess,
            shellAccess = request.shellAccess ?: existing.shellAccess,
            networkAccess = request.networkAccess ?: existing.networkAccess,
            executeCommands = request.executeCommands ?: existing.executeCommands,
            memoryScope = updatedMemoryScope,
            memoryNamespace = when (updatedMemoryScope) {
                "agent" -> existing.memoryNamespace ?: existing.id
                "none" -> null
                else -> null
            },
            tags = request.tags ?: existing.tags,
            enabled = request.enabled ?: existing.enabled,
            responseFormat = request.responseFormat ?: existing.responseFormat,
            maxTurnsOverride = request.maxTurnsOverride ?: existing.maxTurnsOverride,
            version = existing.version + 1,
        )
        val result = localAgentStore.update(updated)
        call.respond(result.orNotFound { "agent not found: $id" }.toResponse())
    }

    delete("/agents/{id}") {
        call.enforceBearerAuth(config.security.apiKey)
        val id = call.requirePathParameter("id", "agent id")
        if (localAgentStore.delete(id)) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Agent not found: $id"))
        }
    }

    post("/agents/run") {
        call.enforceBearerAuth(config.security.apiKey)
        val request = call.receive<AgentRunAgentRequest>()
        val agentDef = localAgentStore.get(request.agentId)
            .orNotFound { "agent not found: ${request.agentId}" }
        if (!agentDef.enabled) {
            throw ValidationException("agent '${request.agentId}' is disabled")
        }
        val instance = SpolaFactory.createFromAgentDefinition(agentDef = agentDef, config = config)
        val result = instance.agent.run(agentDef.systemPrompt, request.goal)
        instance.close()
        call.respond(mapOf("agentId" to request.agentId, "result" to result))
    }
}
