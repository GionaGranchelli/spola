package dev.spola.api

import dev.spola.SpolaConfig
import dev.spola.api.CreateJobRequest
import dev.spola.api.DeleteJobResponse
import dev.spola.api.JobsResponse
import dev.spola.scheduler.SpolaJobStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.request.receive

fun Route.apiJobRoutes(
    config: SpolaConfig,
    jobStore: SpolaJobStore,
) {
    get("/jobs") {
        call.enforceBearerAuth(config.security.apiKey)
        call.respond(JobsResponse(jobStore.list().map { it.toResponse() }))
    }

    post("/jobs") {
        call.enforceBearerAuth(config.security.apiKey)
        val request = call.receive<CreateJobRequest>()
        val job = jobStore.add(
            name = request.name,
            goal = request.goal,
            cronExpression = request.cronExpression,
            enabled = request.enabled,
            workflowDefinitionId = request.workflowDefinitionId,
        )
        call.respond(HttpStatusCode.Created, job.toResponse())
    }

    delete("/jobs/{id}") {
        call.enforceBearerAuth(config.security.apiKey)
        val id = call.requirePathParameter("id", "job id")
        val removed = jobStore.remove(id)
        if (!removed) {
            call.respond(
                HttpStatusCode.NotFound,
                DeleteJobResponse(
                    removed = false,
                    id = id,
                    message = "Scheduled job not found",
                ),
            )
        } else {
            call.respond(
                DeleteJobResponse(
                    removed = true,
                    id = id,
                    message = "Scheduled job removed",
                ),
            )
        }
    }
}
