package dev.spola.api

import dev.spola.SpolaConfig
import dev.spola.ToolRegistry
import dev.spola.api.DeliveryResponse
import dev.spola.api.EmailSendRequest
import dev.spola.api.TelegramSendRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.request.receive

fun Route.apiDeliveryRoutes(
    config: SpolaConfig,
    toolRegistry: ToolRegistry,
) {
    post("/deliver/telegram") {
        call.enforceBearerAuth(config.security.apiKey)
        val request = call.receive<TelegramSendRequest>()
        val tool = toolRegistry.get("telegram_send")
            ?: throw IllegalStateException("telegram_send tool not registered")
        val result = tool.execute(
            mapOf("chat_id" to request.chatId, "text" to request.text),
        )
        call.respond(
            if (result.success) HttpStatusCode.OK else HttpStatusCode.BadGateway,
            DeliveryResponse(success = result.success, message = result.output),
        )
    }

    post("/deliver/email") {
        call.enforceBearerAuth(config.security.apiKey)
        val request = call.receive<EmailSendRequest>()
        val tool = toolRegistry.get("email_send")
            ?: throw IllegalStateException("email_send tool not registered")
        val result = tool.execute(
            mapOf("to" to request.to, "subject" to request.subject, "body" to request.body),
        )
        call.respond(
            if (result.success) HttpStatusCode.OK else HttpStatusCode.BadGateway,
            DeliveryResponse(success = result.success, message = result.output),
        )
    }
}
