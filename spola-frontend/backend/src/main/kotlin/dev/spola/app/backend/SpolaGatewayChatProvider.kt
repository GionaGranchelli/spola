package dev.spola.app.backend

import dev.spola.app.backend.network.OpenAiChatMessage
import dev.spola.app.backend.network.SpolaRestGatewayClient
import dev.spola.app.models.SpolaSessionSettings
import kotlinx.coroutines.flow.collect

class SpolaGatewayChatProvider(
    private val restClient: SpolaRestGatewayClient
) : ChatProvider {
    override val id: String = "spola-gateway"

    override suspend fun generate(
        sessionId: String,
        modelId: String,
        messages: List<dev.spola.app.models.Message>,
        sessionSettings: SpolaSessionSettings?,
        onToken: suspend (String) -> Unit
    ): String {
        // Build Spola Client model ID (e.g., "spola/default" or "spola/agent-id")
        val agentId = sessionSettings?.agentId ?: "default"
        val spolaModel = if (agentId.startsWith("spola/")) agentId else "spola/$agentId"
        
        // Extract all file IDs mentioned in any message [file:id]
        val fileRegex = Regex("\\[file:([a-zA-Z0-9-]+)\\]")
        val allAttachments = messages.flatMap { msg ->
            fileRegex.findAll(msg.content).map { it.groupValues[1] }.toList()
        }.distinct().takeIf { it.isNotEmpty() }

        val openAiMessages = messages.map { msg ->
            OpenAiChatMessage(
                role = msg.role.name.lowercase(),
                content = msg.content.replace(fileRegex, "").trim()
            )
        }

        val overrides = mutableMapOf<String, String>()
        
        // Pass model override if specified
        sessionSettings?.modelId?.let { overrides["x-spola-model"] = it }
        sessionSettings?.thinking?.let { overrides["x-spola-thinking"] = it }

        val fullResponse = StringBuilder()
        restClient.chatCompletionsStream(
            model = spolaModel,
            messages = openAiMessages,
            sessionKey = sessionId,
            agentId = sessionSettings?.agentId,
            overrides = overrides,
            attachments = allAttachments
        ).collect { token ->
            fullResponse.append(token)
            onToken(token)
        }

        return fullResponse.toString()
    }
}
