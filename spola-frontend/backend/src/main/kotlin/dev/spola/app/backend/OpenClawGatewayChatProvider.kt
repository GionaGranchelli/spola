package dev.spola.app.backend

import dev.spola.app.backend.network.OpenAiChatMessage
import dev.spola.app.backend.network.OpenClawRestGatewayClient
import dev.spola.app.models.OpenClawSessionSettings
import kotlinx.coroutines.flow.collect

class OpenClawGatewayChatProvider(
    private val restClient: OpenClawRestGatewayClient
) : ChatProvider {
    override val id: String = "openclaw-gateway"

    override suspend fun generate(
        sessionId: String,
        modelId: String,
        messages: List<dev.spola.app.models.Message>,
        sessionSettings: OpenClawSessionSettings?,
        onToken: suspend (String) -> Unit
    ): String {
        // Build OpenClaw model ID (e.g., "openclaw/default" or "openclaw/agent-id")
        val agentId = sessionSettings?.agentId ?: "default"
        val openClawModel = if (agentId.startsWith("openclaw/")) agentId else "openclaw/$agentId"
        
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
        sessionSettings?.modelId?.let { overrides["x-openclaw-model"] = it }
        sessionSettings?.thinking?.let { overrides["x-openclaw-thinking"] = it }

        val fullResponse = StringBuilder()
        restClient.chatCompletionsStream(
            model = openClawModel,
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
