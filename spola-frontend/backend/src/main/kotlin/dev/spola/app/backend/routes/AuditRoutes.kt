package dev.spola.app.backend.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import dev.spola.app.backend.BackendServices
import dev.spola.app.backend.TrustAuth

fun Route.auditRoutes(services: BackendServices) {
    get("/audit") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@get
        call.respond(services.auditRepository.getAll())
    }
}
