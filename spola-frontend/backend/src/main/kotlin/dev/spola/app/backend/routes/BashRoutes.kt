package dev.spola.app.backend.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import dev.spola.app.backend.BackendServices
import dev.spola.app.backend.TrustAuth
import dev.spola.app.models.*
import kotlinx.serialization.json.Json

fun Route.bashRoutes(services: BackendServices) {
    post("/bash/preview") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
        val request = call.receive<BashCommandRequest>()
        call.respond(services.commandManager.preview(request))
    }

    post("/bash/approve") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
        val preview = call.receive<BashCommandPreview>()
        val approved = services.commandManager.approve(preview.approvalId)
            ?: return@post call.respond(HttpStatusCode.NotFound)
        call.respond(approved)
    }

    post("/bash") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
        val request = call.receive<BashCommandRequest>()
        call.respond(services.commandManager.execute(request))
    }

    post("/command/{approvalId}/input") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
        val id = call.parameters["approvalId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val input = call.receiveText()
        val success = services.commandManager.sendInput(id, input)
        if (success) call.respond(HttpStatusCode.Accepted) else call.respond(HttpStatusCode.NotFound)
    }

    sse("/bash/{approvalId}/stream") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@sse
        val approvalId = call.parameters["approvalId"] ?: return@sse
        val flow = services.flowManager.getCommandFlow(approvalId)
        flow.collect { send(data = Json.encodeToString(CommandStreamEvent.serializer(), it)) }
    }

    sse("/command/{approvalId}/stream") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@sse
        val approvalId = call.parameters["approvalId"] ?: return@sse
        val flow = services.flowManager.getCommandFlow(approvalId)
        flow.collect { send(data = Json.encodeToString(CommandStreamEvent.serializer(), it)) }
    }
}
