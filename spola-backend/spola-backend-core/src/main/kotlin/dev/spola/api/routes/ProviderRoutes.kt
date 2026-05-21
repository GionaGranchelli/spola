package dev.spola.api

import dev.spola.CustomProviderDef
import dev.spola.SpolaConfig
import dev.spola.api.CreateProviderRequest
import dev.spola.api.DeleteProviderResponse
import dev.spola.api.ProviderInfoResponse
import dev.spola.api.ProviderTestRequest
import dev.spola.api.ProviderTestResponse
import dev.spola.api.ProvidersListResponse
import dev.spola.config.SpolaConfigFileStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Routes for managing LLM providers.
 *
 * Built-in providers are resolved from environment variables.
 * Custom providers are stored in config.yaml under `custom_providers`.
 */
fun Route.apiProviderRoutes(
    config: SpolaConfig,
    configStore: SpolaConfigFileStore,
) {
    // ── GET /api/providers — list all available providers ──
    get("/providers") {
        call.enforceBearerAuth(config.security.apiKey)
        val builtinProviders = listOf(
            ProviderInfoResponse(
                name = "openai",
                type = "openai",
                baseUrl = null,
                model = System.getenv("OPENAI_MODEL") ?: "gpt-4o",
                isBuiltin = true,
                hasApiKey = System.getenv("OPENAI_API_KEY") != null,
            ),
            ProviderInfoResponse(
                name = "anthropic",
                type = "anthropic",
                baseUrl = null,
                model = System.getenv("ANTHROPIC_MODEL") ?: "claude-sonnet-4-20250514",
                isBuiltin = true,
                hasApiKey = System.getenv("ANTHROPIC_API_KEY") != null,
            ),
            ProviderInfoResponse(
                name = "openai-compat",
                type = "openai-compat",
                baseUrl = System.getenv("OPENAI_BASE_URL") ?: "http://localhost:8090/v1",
                model = System.getenv("OPENAI_COMPAT_MODEL") ?: "gpt-4o",
                isBuiltin = true,
                hasApiKey = System.getenv("OPENAI_COMPAT_API_KEY") != null || System.getenv("OPENAI_API_KEY") != null,
            ),
            ProviderInfoResponse(
                name = "ollama",
                type = "ollama",
                baseUrl = System.getenv("OLLAMA_BASE_URL") ?: "http://localhost:11434/v1",
                model = "llama3",
                isBuiltin = true,
                hasApiKey = true,
            ),
            ProviderInfoResponse(
                name = "google",
                type = "google",
                baseUrl = System.getenv("GOOGLE_BASE_URL") ?: "https://generativelanguage.googleapis.com/v1beta/openai",
                model = System.getenv("GOOGLE_MODEL") ?: "gemini-2.5-pro",
                isBuiltin = true,
                hasApiKey = System.getenv("GOOGLE_API_KEY") != null,
            ),
        )

        val customProviders = config.provider.customProviders.map { cp ->
            ProviderInfoResponse(
                name = cp.name,
                type = cp.type,
                baseUrl = cp.baseUrl,
                model = cp.model,
                isBuiltin = false,
                hasApiKey = cp.apiKey != null,
            )
        }

        call.respond(ProvidersListResponse(builtinProviders + customProviders))
    }

    // ── POST /api/providers — add a custom provider ──
    post("/providers") {
        call.enforceBearerAuth(config.security.apiKey)
        val body = call.receive<CreateProviderRequest>()
        val currentConfig = configStore.load()

        // Validate uniqueness
        val exists = currentConfig.provider.customProviders.any { it.name == body.name }
        if (exists) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "Provider '${body.name}' already exists"))
            return@post
        }

        val newProvider = CustomProviderDef(
            name = body.name,
            type = body.type,
            baseUrl = body.baseUrl,
            apiKey = body.apiKey?.takeIf { it.isNotBlank() },
            model = body.model?.takeIf { it.isNotBlank() },
        )

        val updatedConfig = currentConfig.copy(
            provider = currentConfig.provider.copy(
                customProviders = currentConfig.provider.customProviders + newProvider,
            ),
        )
        configStore.save(updatedConfig)

        call.respond(
            HttpStatusCode.Created,
            ProviderInfoResponse(
                name = newProvider.name,
                type = newProvider.type,
                baseUrl = newProvider.baseUrl,
                model = newProvider.model,
                isBuiltin = false,
                hasApiKey = newProvider.apiKey != null,
            ),
        )
    }

    // ── DELETE /api/providers/{name} — remove a custom provider ──
    delete("/providers/{name}") {
        call.enforceBearerAuth(config.security.apiKey)
        val name = call.parameters["name"] ?: return@delete run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing provider name"))
        }

        val currentConfig = configStore.load()
        val filtered = currentConfig.provider.customProviders.filterNot { it.name == name }

        if (filtered.size == currentConfig.provider.customProviders.size) {
            call.respond(HttpStatusCode.NotFound, DeleteProviderResponse(deleted = false, name = name))
            return@delete
        }

        val updatedConfig = currentConfig.copy(provider = currentConfig.provider.copy(customProviders = filtered))
        configStore.save(updatedConfig)

        call.respond(DeleteProviderResponse(deleted = true, name = name))
    }

    // ── POST /api/provider/test — test a provider connection ──
    post("/provider/test") {
        call.enforceBearerAuth(config.security.apiKey)
        val body = try {
            call.receive<ProviderTestRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ProviderTestResponse(success = false, message = "Invalid request body"))
            return@post
        }

        try {
            val client = HttpClient(CIO) {
                engine {
                    requestTimeout = 10_000
                }
            }
            val response = client.get(body.baseUrl.trimEnd('/') + "/models") {
                if (!body.apiKey.isNullOrBlank()) {
                    header("Authorization", "Bearer ${body.apiKey}")
                }
            }
            val statusCode = response.status.value
            val success = statusCode in 200..299
            call.respond(
                ProviderTestResponse(
                    success = success,
                    status = statusCode,
                    message = if (success) "Connected successfully" else "HTTP $statusCode",
                ),
            )
        } catch (e: Exception) {
            call.respond(
                ProviderTestResponse(
                    success = false,
                    message = e.message ?: "Connection failed",
                ),
            )
        }
    }
}
