package dev.spola.scheduler

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GolemCronParserTest {

    @Test
    fun `parse converts five-field expression into the next matching instant`() {
        val schedule = GolemCronParser.parse("*/5 * * * *", ZoneId.of("UTC"))

        val nextFire = schedule.nextFireAfter(Instant.parse("2026-05-11T10:02:00Z"))

        assertEquals(Instant.parse("2026-05-11T10:05:00Z"), nextFire)
    }

    @Test
    fun `parse rejects blank expression`() {
        assertFailsWith<IllegalArgumentException> {
            GolemCronParser.parse("   ", ZoneId.of("UTC"))
        }
    }

    @Test
    fun `parse rejects expressions that are not five-field`() {
        assertFailsWith<IllegalArgumentException> {
            GolemCronParser.parse("0 */5 * * * *", ZoneId.of("UTC"))
        }
    }
}
