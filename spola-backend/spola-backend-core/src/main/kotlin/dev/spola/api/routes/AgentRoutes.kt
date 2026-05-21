package dev.spola.api

import dev.spola.SpolaConfig
import dev.spola.ToolRegistry
import dev.spola.api.AgentRunHandler
import dev.spola.api.AgentRunRequest
import dev.spola.api.AgentRunState
import dev.spola.api.AgentStatusResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.request.receive
import io.ktor.server.sse.SSEServerContent

fun Route.apiAgentRoutes(
    config: SpolaConfig,
    agentRunHandler: AgentRunHandler,
    streamHandler: StreamHandler,
    runState: AgentRunState?,
    toolRegistry: ToolRegistry,
) {
    post("/agent/run") {
        call.enforceBearerAuth(config.apiKey)
        val request = call.receive<AgentRunRequest>()
        if (request.goal.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "goal must not be blank"))
            return@post
        }
        call.respond(agentRunHandler.run(request))
    }

    post("/agent/run/stream") {
        call.enforceBearerAuth(config.apiKey)
        val request = call.receive<AgentRunRequest>()
        call.respond(SSEServerContent(call) {
            streamHandler.stream(this, request)
        })
    }

    get("/agent/status") {
        call.enforceBearerAuth(config.apiKey)
        call.respond(
            AgentStatusResponse(
                model = config.model,
                provider = config.provider,
                maxTurns = config.maxTurns,
                workingDirectory = config.workingDirectory,
                toolCount = toolRegistry.list().size,
                running = (runState?.isRunning() == true) || agentRunHandler.isRunning(),
            ),
        )
    }
}
