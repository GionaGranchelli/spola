package dev.spola.scheduler

import dev.tramai.scheduler.CronSchedule
import dev.tramai.scheduler.at
import java.time.ZoneId

/**
 * Parses Spola's user-facing five-field cron expressions into TramAI schedules.
 */
object SpolaCronParser {

    fun parse(
        expression: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): CronSchedule {
        val trimmed = expression.trim()
        require(trimmed.isNotEmpty()) { "Cron expression must not be blank" }

        val parts = trimmed.split(Regex("\\s+"))
        require(parts.size == 5) {
            "Spola cron expression '$expression' must have exactly 5 fields: minute hour day-of-month month day-of-week"
        }

        return at("0 $trimmed", zoneId = zoneId)
    }
}
