package dev.spola.app.backend.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import dev.spola.app.backend.TrustAuth
import dev.spola.app.backend.BackendServices
import dev.spola.app.backend.CatalogResponse
import dev.spola.app.backend.SUPPORTED_PROVIDERS
import dev.spola.app.models.ModelInfo
import dev.spola.app.models.OpenClawOptions
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

fun Route.modelRoutes(services: BackendServices) {
    get("/meta") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@get
        call.respond(services.backendMetaService.current())
    }

    get("/models") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@get
        val response = runCatching { services.modelCatalogService.listModels() }
            .getOrElse {
                println("Model catalog fatal error: ${it.message}")
                CatalogResponse(value = emptyList<ModelInfo>(), warnings = listOf(it.message ?: "unknown error"))
            }
        response.warnings.forEach { println("Model catalog warning: $it") }
        val payload = runCatching {
            Json.encodeToString(ListSerializer(ModelInfo.serializer()), response.value)
        }.getOrElse {
            println("Model catalog serialization failure: ${it.message}")
            "[]"
        }
        call.respondText(payload, ContentType.Application.Json, HttpStatusCode.OK)
    }

    get("/providers") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@get
        call.respond(SUPPORTED_PROVIDERS)
    }

    get("/openclaw/options") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@get
        val response = runCatching { services.modelCatalogService.getOpenClawOptions() }
            .getOrElse {
                println("OpenClaw options fatal error: ${it.message}")
                CatalogResponse(value = OpenClawOptions(), warnings = listOf(it.message ?: "unknown error"))
            }
        response.warnings.forEach { println("OpenClaw options warning: $it") }
        val payload = runCatching {
            Json.encodeToString(OpenClawOptions.serializer(), response.value)
        }.getOrElse {
            println("OpenClaw options serialization failure: ${it.message}")
            Json.encodeToString(OpenClawOptions.serializer(), OpenClawOptions())
        }
        call.respondText(payload, ContentType.Application.Json, HttpStatusCode.OK)
    }
}
