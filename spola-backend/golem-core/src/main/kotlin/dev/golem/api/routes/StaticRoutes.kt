package dev.spola.api

import io.ktor.http.ContentType
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.apiStaticRoutes() {
    get("/web") {
        val indexStream = this::class.java.classLoader.getResourceAsStream("web/index.html")
        if (indexStream != null) {
            call.respondBytes(indexStream.readBytes(), ContentType.Text.Html)
        } else {
            call.respondText("Not Found", ContentType.Text.Plain, io.ktor.http.HttpStatusCode.NotFound)
        }
    }

    get("/web/{path...}") {
        val rawPath = call.parameters["path"] ?: "index.html"
        if (rawPath.contains("..")) {
            call.respondText("Forbidden", ContentType.Text.Plain, io.ktor.http.HttpStatusCode.Forbidden)
            return@get
        }
        val path = rawPath
        val resourceStream = this::class.java.classLoader.getResourceAsStream("web/$path")
        if (resourceStream != null) {
            val bytes = resourceStream.readBytes()
            val contentType = when {
                path.endsWith(".html") -> ContentType.Text.Html
                path.endsWith(".css") -> ContentType.Text.CSS
                path.endsWith(".js") -> ContentType.parse("application/javascript")
                path.endsWith(".png") -> ContentType.Image.PNG
                path.endsWith(".svg") -> ContentType.parse("image/svg+xml")
                path.endsWith(".ico") -> ContentType.parse("image/x-icon")
                path.endsWith(".json") -> ContentType.Application.Json
                else -> ContentType.Text.Plain
            }
            call.respondBytes(bytes, contentType)
        } else {
            val indexStream = this::class.java.classLoader.getResourceAsStream("web/index.html")
            if (indexStream != null) {
                call.respondBytes(indexStream.readBytes(), ContentType.Text.Html)
            } else {
                call.respondText("Not Found", ContentType.Text.Plain, io.ktor.http.HttpStatusCode.NotFound)
            }
        }
    }
}
