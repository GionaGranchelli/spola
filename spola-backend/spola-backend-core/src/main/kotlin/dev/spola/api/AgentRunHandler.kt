package dev.spola.api

import dev.spola.AssistantMessage
import dev.spola.ChatMessage
import dev.spola.SpolaAgent
import dev.spola.SpolaConfig
import dev.spola.SpolaFactory
import dev.spola.SpolaInstance
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
        require(request.goal.isNotBlank()) { "goal must not be blank" }

        return runState.track {
            val instance = instanceFactory(baseConfig, request.model)
            try {
                val persona = request.persona ?: instance.persona
                // Inject stored memories into the system prompt
                val enrichedPersona = try {
                    val memories = instance.memoryStore.listAll()
                    if (memories.isNotEmpty()) {
                        val memoryBlock = memories.joinToString("\n") { "- **${it.key}**: ${it.value}" }
                        "$persona\n\n## Context from Memory\n$memoryBlock\n\nReview the context above. Use `memory_search` to find more specific facts or `memory_save` to store new ones."
                    } else {
                        persona
                    }
                } catch (e: Exception) {
                    persona
                }
                val result = instance.agent.run(enrichedPersona, request.goal)
                val conversation = instance.agent.getConversation()
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
        observer: dev.spola.AgentRunObserver? = null,
    ): String = agent.run(persona, goal, observer)
}
