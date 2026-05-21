package dev.spola.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import dev.spola.SpolaConfig
import dev.spola.SpolaFactory
import dev.spola.config.MetricsConfig
import dev.tramai.orchestration.InMemoryWorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowStateCodec
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpolaWorkflowStateCodecTest {

    private val codec: WorkflowStateCodec<SpolaState> = SpolaWorkflowStateCodec()

    // -- encode/decode round-trip tests -----------------------------------------------

    @Test
    fun `encode and decode round-trips a SpolaState`() {
        val original = SpolaState(
            goal = "test-goal",
            config = SpolaConfig(),
            conversation = emptyList(),
            turnCount = 42,
            intermediateResults = mapOf("key-a" to "value-a", "key-b" to "value-b"),
            result = "completed successfully",
        )

        val encoded = codec.encode(original)
        val decoded = codec.decode(encoded)

        assertEquals(original.goal, decoded.goal)
        assertEquals(original.turnCount, decoded.turnCount)
        assertEquals(original.intermediateResults, decoded.intermediateResults)
        assertEquals(original.result, decoded.result)
    }

    @Test
    fun `encode round-trips a minimal SpolaState`() {
        val original = SpolaState(goal = "minimal")
        val encoded = codec.encode(original)
        val decoded = codec.decode(encoded)
        assertEquals(original.goal, decoded.goal)
        assertEquals(0, decoded.turnCount)
    }

    @Test
    fun `encode round-trips a SpolaState with null result`() {
        val original = SpolaState(
            goal = "no-result-yet",
            turnCount = 1,
        )
        val encoded = codec.encode(original)
        val decoded = codec.decode(encoded)
        assertEquals(original.goal, decoded.goal)
        assertEquals(null, decoded.result)
    }

    // -- JSON format tests ------------------------------------------------------------

    @Test
    fun `encode produces valid JSON with expected fields`() {
        val state = SpolaState(
            goal = "json-test",
            turnCount = 7,
            result = "some-result",
        )
        val json = codec.encode(state)

        // Verify it's parseable JSON
        val mapper = ObjectMapper()
        val tree = mapper.readTree(json)
        assertNotNull(tree)

        // Verify expected fields
        assertEquals("json-test", tree.get("goal").asText())
        assertEquals(7, tree.get("turnCount").asInt())
        assertEquals("some-result", tree.get("result").asText())
    }

    @Test
    fun `encode produces parseable JSON for empty state`() {
        val state = SpolaState(goal = "empty-state")
        val json = codec.encode(state)

        val mapper = ObjectMapper()
        val tree = mapper.readTree(json)
        assertNotNull(tree)
        assertEquals("empty-state", tree.get("goal").asText())
    }

    // -- configurePersistence tests ---------------------------------------------------

    @Test
    fun `configurePersistence creates WorkflowPersistence with correct stateCodec`() {
        val persistence = SpolaFactory.configurePersistence()
        assertNotNull(persistence)
        assertNotNull(persistence.checkpointStore)
        assertTrue(persistence.deleteCheckpointOnCompletion)
    }

    @Test
    fun `configurePersistence defaults to SpolaWorkflowStateCodec`() {
        val persistence = SpolaFactory.configurePersistence()
        assertIs<SpolaWorkflowStateCodec>(persistence.stateCodec)
    }

    // -- runWorkflow with persistence -------------------------------------------------

    @Test
    fun `runWorkflow with persistence performs checkpointing`() = runTest {
        val initialState = SpolaState(goal = "persistence-test")
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val persistence = WorkflowPersistence(
            checkpointStore = checkpointStore,
            stateCodec = codec,
            deleteCheckpointOnCompletion = false,
        )

        val result = SpolaFactory.runWorkflow(
            name = "persistence-test-wf",
            initialState = initialState,
            config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false)),
            persistence = persistence,
            workflow = {
                localStep("transform") { state, _ ->
                    state.copy(
                        result = "persisted: ${state.goal}",
                        turnCount = state.turnCount + 1,
                    )
                }
            },
            resultSelector = { it.result ?: "" },
        )

        assertEquals("persisted: persistence-test", result)

        // Verify checkpoint was written
        val checkpoints = checkpointStore.listCheckpoints()
        assertTrue(checkpoints.isNotEmpty(), "Expected at least one checkpoint to be saved")
    }
}
