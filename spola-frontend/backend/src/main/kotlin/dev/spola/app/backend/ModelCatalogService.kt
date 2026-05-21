package dev.spola.app.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import dev.spola.app.backend.network.SpolaRestGatewayClient
import dev.spola.app.models.ModelInfo
import dev.spola.app.models.SpolaAgentInfo
import dev.spola.app.models.SpolaOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CatalogResponse<T>(
    val value: T,
    val warnings: List<String> = emptyList(),
)

interface ModelCatalogService {
    suspend fun listModels(): CatalogResponse<List<ModelInfo>>
    suspend fun getSpolaOptions(): CatalogResponse<SpolaOptions>
}

fun interface OllamaModelSource {
    suspend fun listModels(): List<ModelInfo>
}

class KtorOllamaModelSource(
    private val client: HttpClient,
    private val baseUrl: String = "http://localhost:11434",
) : OllamaModelSource {
    override suspend fun listModels(): List<ModelInfo> {
        val response = runCatching { client.get("$baseUrl/api/tags") }.getOrNull() ?: return emptyList()
        val body = response.bodyAsText()
        if (body.isBlank()) return emptyList()
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        return json["models"]?.jsonArray?.mapNotNull {
            val name = it.jsonObject["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (name.isBlank()) null else ModelInfo(id = name, name = name, provider = PROVIDER_OLLAMA)
        }.orEmpty()
    }
}

private const val SPOLA_PREFIX = "spola/"

class DefaultModelCatalogService(
    private val ollamaModelSource: OllamaModelSource,
    private val restGatewayClient: SpolaRestGatewayClient,
) : ModelCatalogService {
    override suspend fun listModels(): CatalogResponse<List<ModelInfo>> {
        val warnings = mutableListOf<String>()

        val ollamaModels = runCatching { ollamaModelSource.listModels() }
            .onFailure { warnings += "Ollama models unavailable: ${it.message}" }
            .getOrDefault(emptyList())

        val spolaModels = runCatching { restGatewayClient.getModels() }
            .onFailure { warnings += "Spola Client models unavailable: ${it.message}" }
            .getOrDefault(emptyList())
            .map { model ->
                ModelInfo(
                    id = model.id,
                    name = model.id.removePrefix(SPOLA_PREFIX),
                    provider = "spola"
                )
            }

        val merged = dedupeModels(ollamaModels + spolaModels)
            .sortedWith(compareBy<ModelInfo> { it.provider.lowercase() }.thenBy { it.name.lowercase() })

        return CatalogResponse(value = merged, warnings = warnings)
    }

    override suspend fun getSpolaOptions(): CatalogResponse<SpolaOptions> {
        val warnings = mutableListOf<String>()

        val spolaModels = runCatching { restGatewayClient.getModels() }
            .onFailure { warnings += "Spola Client models unavailable: ${it.message}" }
            .getOrDefault(emptyList())

        // Filter for agents (those that start with spola/ and are not spola/default).
        val agents = spolaModels
            .filter { it.id.startsWith(SPOLA_PREFIX) && it.id != "${SPOLA_PREFIX}default" }
            .map { model ->
                SpolaAgentInfo(
                    id = model.id.removePrefix(SPOLA_PREFIX),
                    name = model.id.removePrefix(SPOLA_PREFIX),
                    isDefault = false // Can be improved if we know the default agent
                )
            }

        return CatalogResponse(
            value = SpolaOptions(
                agents = agents,
                models = emptyList(),
            ),
            warnings = warnings,
        )
    }

    private fun dedupeModels(models: List<ModelInfo>): List<ModelInfo> {
        val seen = mutableSetOf<String>()
        val deduped = mutableListOf<ModelInfo>()
        for (model in models) {
            val key = canonicalKey(model)
            if (seen.add(key)) {
                deduped += model
            }
        }
        return deduped
    }

    private fun canonicalKey(model: ModelInfo): String {
        val provider = model.provider.lowercase()
        val id = model.id.trim()
        val normalizedId = when {
            id.startsWith("$provider/") -> id.substringAfter("/")
            id.startsWith("${PROVIDER_OLLAMA}/") && provider == PROVIDER_OLLAMA -> id.substringAfter("/")
            else -> id
        }
        return "$provider/${normalizedId.lowercase().removeSuffix(":latest")}"
    }
}
