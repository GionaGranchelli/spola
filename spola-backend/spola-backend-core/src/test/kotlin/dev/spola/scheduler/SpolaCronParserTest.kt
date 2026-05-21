package dev.spola.scheduler

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpolaCronParserTest {

    @Test
    fun `parse converts five-field expression into the next matching instant`() {
        val schedule = SpolaCronParser.parse("*/5 * * * *", ZoneId.of("UTC"))

        val nextFire = schedule.nextFireAfter(Instant.parse("2026-05-11T10:02:00Z"))

        assertEquals(Instant.parse("2026-05-11T10:05:00Z"), nextFire)
    }

    @Test
    fun `parse rejects blank expression`() {
        assertFailsWith<IllegalArgumentException> {
            SpolaCronParser.parse("   ", ZoneId.of("UTC"))
        }
    }

    @Test
    fun `parse rejects expressions that are not five-field`() {
        assertFailsWith<IllegalArgumentException> {
            SpolaCronParser.parse("0 */5 * * * *", ZoneId.of("UTC"))
        }
    }
}
