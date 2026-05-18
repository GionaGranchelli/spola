package dev.spola.api

import dev.spola.api.MetricsHistoryResponse
import dev.spola.api.MetricsPointResponse
import dev.spola.metrics.GolemMetrics
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.apiMetricsRoutes(
    golemMetrics: GolemMetrics?,
) {
    get("/metrics") {
        val metrics = golemMetrics ?: GolemMetrics()
        val prometheusText = metrics.renderPrometheusText()
        call.respondText(
            text = prometheusText,
            contentType = ContentType.Text.Plain,
        )
    }

    get("/metrics/history") {
        val metrics = golemMetrics ?: GolemMetrics()
        val snapshots = metrics.getHistory()
        call.respond(
            MetricsHistoryResponse(
                metrics = snapshots.map { snapshot ->
                    MetricsPointResponse(
                        timestamp = snapshot.timestamp,
                        agentRunsTotal = snapshot.agentRunsTotal,
                        agentTurnsTotal = snapshot.agentTurnsTotal,
                        toolCallsTotal = snapshot.toolCallsTotal,
                        llmCallsTotal = snapshot.llmCallsTotal,
                        llmTokensTotal = snapshot.llmTokensTotal,
                    )
                },
            ),
        )
    }
}
