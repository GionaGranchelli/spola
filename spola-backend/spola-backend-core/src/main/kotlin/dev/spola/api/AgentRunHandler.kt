package dev.spola.api

import dev.spola.AssistantMessage
import dev.spola.ChatMessage
import dev.spola.SpolaAgent
import dev.spola.SpolaConfig
import dev.spola.SpolaFactory
import dev.spola.SpolaInstance
import dev.spola.SystemMessage
import java.util.concurrent.atomic.AtomicInteger

class AgentRunState {
    private val activeRuns = AtomicInteger(0)

    fun isRunning(): Boolean = activeRuns.get() > 0

    suspend fun <T> track(block: suspend () -> T): T {
        activeRuns.incrementAndGet()
        return try {
            block()
        } finally {
            activeRuns.decrementAndGet()
        }
    }
}

class AgentRunHandler(
    private val baseConfig: SpolaConfig = SpolaConfig(),
    private val instanceFactory: suspend (SpolaConfig, String?) -> SpolaInstance = { config, model ->
        SpolaFactory.create(config = config, effectiveModel = model)
    },
    private val runState: AgentRunState = AgentRunState(),
) {
    data class CompletedRun(
        val result: String,
        val turns: Int,
        val conversation: List<ChatMessage>,
    )

    fun isRunning(): Boolean = runState.isRunning()

    suspend fun run(request: AgentRunRequest): AgentRunResponse {
        val completedRun = runWithConversation(request)
        return AgentRunResponse(
            result = completedRun.result,
            turns = completedRun.turns,
        )
    }

    suspend fun runWithConversation(request: AgentRunRequest): CompletedRun {
        return runWithConversation(request, preloadedConversation = null)
    }

    suspend fun runWithConversation(
        request: AgentRunRequest,
        preloadedConversation: List<ChatMessage>?,
    ): CompletedRun {
        require(request.goal.isNotBlank()) { "goal must not be blank" }

        return runState.track {
            val instance = instanceFactory(baseConfig, request.model)
            try {
                val persona = request.persona ?: instance.persona
                val enrichedPersona = enrichPersonaWithMemory(instance, persona)
                val transcript = preloadedConversation?.toMutableList()?.let {
                    preloadTranscript(it, enrichedPersona)
                }
                val result = if (transcript != null) {
                    instance.agent.runFull(enrichedPersona, request.goal, transcript)
                } else {
                    instance.agent.run(enrichedPersona, request.goal)
                }
                val conversation = transcript ?: instance.agent.getConversation()
                val turns = conversation.filterIsInstance<AssistantMessage>().size
                CompletedRun(
                    result = result,
                    turns = turns,
                    conversation = conversation,
                )
            } finally {
                instance.close()
            }
        }
    }

    suspend fun createInstance(request: AgentRunRequest): SpolaInstance {
        require(request.goal.isNotBlank()) { "goal must not be blank" }
        return instanceFactory(baseConfig, request.model)
    }

    suspend fun <T> trackRun(block: suspend () -> T): T = runState.track(block)

    suspend fun runAgent(
        agent: SpolaAgent,
        persona: String,
        goal: String,
        preloadedConversation: MutableList<ChatMessage>? = null,
        observer: dev.spola.AgentRunObserver? = null,
    ): String {
        val transcript = preloadedConversation?.let {
            preloadTranscript(it, persona)
        }
        return if (transcript != null) {
            agent.runFull(persona, goal, transcript, observer)
        } else {
            agent.run(persona, goal, observer)
        }
    }

    suspend fun enrichPersonaWithMemory(instance: SpolaInstance, persona: String): String {
        return try {
            val memories = instance.memoryStore.listAll()
            if (memories.isEmpty()) {
                persona
            } else {
                val memoryBlock = memories.joinToString("\n") { "- **${it.key}**: ${it.value}" }
                "$persona\n\n## Context from Memory\n$memoryBlock\n\nReview the context above. Use `memory_search` to find more specific facts or `memory_save` to store new ones."
            }
        } catch (_: Exception) {
            persona
        }
    }

    private fun preloadTranscript(
        transcript: MutableList<ChatMessage>,
        persona: String,
    ): MutableList<ChatMessage> {
        val existingSystem = transcript.firstOrNull() as? SystemMessage
        return when {
            existingSystem == null -> {
                transcript.add(0, SystemMessage(persona))
                transcript
            }
            existingSystem.content == persona -> transcript
            else -> {
                transcript[0] = SystemMessage(persona)
                transcript
            }
        }
    }
}
