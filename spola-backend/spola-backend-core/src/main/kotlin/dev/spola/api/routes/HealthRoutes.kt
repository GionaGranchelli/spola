package dev.spola.api

import dev.spola.SpolaVersion
import dev.spola.api.HealthResponse
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.apiHealthRoutes(version: String = SpolaVersion.VERSION) {
    get("/health") {
        call.respond(HealthResponse(status = "ok", version = version))
    }
}
