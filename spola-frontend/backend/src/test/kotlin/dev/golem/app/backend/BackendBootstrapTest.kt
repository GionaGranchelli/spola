package it.openclaw.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackendBootstrapTest {
    @Test
    fun pickBackendPortPrefersRequestedPortWhenAvailable() {
        val selected = pickBackendPort(
            preferredPort = 9090,
            scanWindow = 3,
            isAvailable = { it == 9090 || it == 9091 }
        )
        assertEquals(9090, selected)
    }

    @Test
    fun pickBackendPortFallsForwardWhenRequestedPortIsBusy() {
        val selected = pickBackendPort(
            preferredPort = 9090,
            scanWindow = 3,
            isAvailable = { it == 9092 }
        )
        assertEquals(9092, selected)
    }

    @Test
    fun pickBackendPortFailsWhenNoCandidatePortIsAvailable() {
        assertFailsWith<IllegalStateException> {
            pickBackendPort(
                preferredPort = 9090,
                scanWindow = 2,
                isAvailable = { false }
            )
        }
    }
}
