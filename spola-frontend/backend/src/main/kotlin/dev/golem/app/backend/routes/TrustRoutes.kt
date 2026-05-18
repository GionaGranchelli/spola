package it.openclaw.backend.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import it.openclaw.backend.BackendServices
import it.openclaw.backend.TrustAuth
import it.openclaw.models.PairingInfo
import it.openclaw.models.TrustRotationResponse
import java.util.*

fun Route.trustRoutes(services: BackendServices) {
    post("/pairing/request") {
        val trust = TrustAuth.currentTrust(services.stateStore) ?: return@post call.respond(HttpStatusCode.Unauthorized, "Trust required")
        call.respond(PairingInfo(trust.host, trust.port, trust.token, trust.trustId))
    }

    post("/pairing/confirm") {
        val incoming = call.receive<PairingInfo>()
        val trust = TrustAuth.currentTrust(services.stateStore) ?: return@post call.respond(HttpStatusCode.Unauthorized, "Trust required")
        if (incoming.token != trust.token) {
            return@post call.respond(HttpStatusCode.Unauthorized, "Invalid token")
        }
        call.respond(trust)
    }

    post("/trust/revoke") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
        services.commandManager.invalidateApprovals()
        services.stateStore.revokeTrustedHost()
        services.auditRepository.log("trust.revoke", details = "revoked by api")
        call.respond(mapOf("revoked" to true))
    }

    post("/trust/rotate") {
        val trust = TrustAuth.requireTrust(call, services.stateStore) ?: return@post
        services.commandManager.invalidateApprovals()
        val rotated = trust.copy(
            token = UUID.randomUUID().toString(),
            rotatedAt = System.currentTimeMillis(),
            previousToken = trust.token,
        )
        services.stateStore.saveTrustedHost(rotated)
        services.auditRepository.log("trust.rotate", details = "token rotated")
        call.respond(TrustRotationResponse(rotated, rotated.token))
    }
}
