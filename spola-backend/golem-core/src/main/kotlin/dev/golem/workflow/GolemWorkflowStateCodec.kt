package dev.spola.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import dev.tramai.orchestration.WorkflowStateCodec

/**
 * Jackson-based [WorkflowStateCodec] for [GolemState].
 *
 * Uses Jackson with the Kotlin module for JSON serialization
 * of workflow state into checkpoints.
 */
class GolemWorkflowStateCodec(
    private val objectMapper: ObjectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
    },
) : WorkflowStateCodec<GolemState> {

    override fun encode(state: GolemState): String =
        objectMapper.writeValueAsString(state)

    override fun decode(payload: String): GolemState =
        objectMapper.readValue(payload, GolemState::class.java)
}
