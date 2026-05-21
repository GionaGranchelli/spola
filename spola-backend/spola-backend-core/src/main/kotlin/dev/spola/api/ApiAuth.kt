package dev.spola.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond

internal class MissingApiKeyException : IllegalStateException("missing api key")

internal class InvalidApiKeyException(message: String = "invalid api key") : IllegalStateException(message)

internal object ApiAuth {
    fun validateBearer(expectedApiKey: String?, authorizationHeader: String?) {
        if (expectedApiKey.isNullOrBlank()) return
        val provided = authorizationHeader
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.trim()

        validateValue(expectedApiKey, provided)
    }

    fun validateApiKey(expectedApiKey: String?, providedApiKey: String?) {
        if (expectedApiKey.isNullOrBlank()) return
        validateValue(expectedApiKey, providedApiKey?.trim())
    }

    /**
     * Validate API key with awareness of the listening host.
     *
     * Security rules:
     * - If `insecure` is true: allow null/blank auth on any host (opt-in relaxation)
     * - If host is localhost (127.0.0.1) and key is null: allow no-auth access
     * - If host is NOT localhost and key is null/blank: REQUIRE an API key
     * - If key is set: always validate it (existing behavior)
     */
    fun validateApiKeyForHost(
        expectedApiKey: String?,
        providedApiKey: String?,
        host: String,
        insecure: Boolean = false,
    ) {
        // Insecure mode: allow null auth on any host
        if (insecure) return

        val isLocalhost = host == "127.0.0.1" || host == "localhost" || host == "::1"

        // No API key configured
        if (expectedApiKey.isNullOrBlank()) {
            // Remote access without a key is a security risk
            if (!isLocalhost) {
                throw InvalidApiKeyException(
                    "API key required for remote access. " +
                        "Set SPOLA_API_KEY (legacy GOLEM_API_KEY also works) or --api-key, or use 127.0.0.1 for local-only access.",
                )
            }
            return // Localhost with no key configured is fine
        }

        // API key is configured — always validate
        validateApiKey(expectedApiKey, providedApiKey)
    }

    private fun validateValue(expectedApiKey: String, providedApiKey: String?) {
        when {
            providedApiKey.isNullOrEmpty() -> throw MissingApiKeyException()
            providedApiKey != expectedApiKey -> throw InvalidApiKeyException()
        }
    }
}

internal suspend fun ApplicationCall.enforceBearerAuth(
    expectedApiKey: String?,
    host: String = "127.0.0.1",
    insecure: Boolean = false,
) {
    val provided = request.header("Authorization")
        ?.takeIf { it.startsWith("Bearer ") }
        ?.removePrefix("Bearer ")
        ?.trim()
    ApiAuth.validateApiKeyForHost(expectedApiKey, provided, host, insecure)
}

internal suspend fun ApplicationCall.respondAuthFailure(cause: Throwable) {
    when (cause) {
        is MissingApiKeyException -> respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing api key"))
        is InvalidApiKeyException -> respond(HttpStatusCode.Forbidden, mapOf("error" to "invalid api key"))
        else -> throw cause
    }
}
