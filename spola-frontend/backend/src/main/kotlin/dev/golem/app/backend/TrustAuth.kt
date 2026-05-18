package dev.spola.app.backend

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import dev.spola.app.models.TrustState
import dev.spola.app.state.AppStateStore

object TrustAuth {
    fun currentTrust(stateStore: AppStateStore): TrustState? {
        return runCatching { stateStore.loadTrustedHost()?.takeIf { it.active } }
            .onFailure { println("Failed to load trust state: ${it.message}") }
            .getOrNull()
    }

    suspend fun requireTrust(call: ApplicationCall, stateStore: AppStateStore): TrustState? {
        val trust = currentTrust(stateStore)
        if (trust == null) {
            call.respond(HttpStatusCode.Unauthorized, "Trust required")
            return null
        }
        val bearer = call.request.header(HttpHeaders.Authorization)
        if (bearer != "Bearer ${trust.token}") {
            call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
            return null
        }
        return trust
    }
}
