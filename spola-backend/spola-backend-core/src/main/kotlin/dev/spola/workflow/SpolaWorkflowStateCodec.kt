package dev.spola.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import dev.tramai.orchestration.WorkflowStateCodec

/**
 * Jackson-based [WorkflowStateCodec] for [SpolaState].
 *
 * Uses Jackson with the Kotlin module for JSON serialization
 * of workflow state into checkpoints.
 */
class SpolaWorkflowStateCodec(
    private val objectMapper: ObjectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
    },
) : WorkflowStateCodec<SpolaState> {

    override fun encode(state: SpolaState): String =
        objectMapper.writeValueAsString(state)

    override fun decode(payload: String): SpolaState =
        objectMapper.readValue(payload, SpolaState::class.java)
}
