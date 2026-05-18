package dev.spola.app.backend.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import dev.spola.app.backend.TrustAuth
import dev.spola.app.backend.BackendServices
import dev.spola.app.models.SynthesizeRequest
import java.io.ByteArrayOutputStream

fun Route.speechRoutes(services: BackendServices) {
    post("/speech/transcribe") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
        val multipart = call.receiveMultipart()
        var audioBytes: ByteArray? = null
        
        multipart.forEachPart { part ->
            if (part is PartData.FileItem) {
                val os = ByteArrayOutputStream()
                part.provider().copyTo(os)
                audioBytes = os.toByteArray()
            }
            part.dispose()
        }
        
        if (audioBytes != null) {
            val result = services.speechService.stt.transcribe(audioBytes)
            call.respond(result)
        } else {
            call.respond(HttpStatusCode.BadRequest, "No audio found")
        }
    }

    post("/speech/synthesize") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
        val request = call.receive<SynthesizeRequest>()
        val audio = services.speechService.tts.synthesize(request.text, request.voice)
        call.respondBytes(audio, ContentType.parse("audio/wav"))
    }
}
