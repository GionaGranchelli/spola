package dev.spola.api

import dev.spola.api.PairingInfoResponse
import dev.spola.api.detectLanIp
import dev.spola.api.generateQrCode
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json

fun Route.apiPairingRoutes(
    golemPairingToken: String,
    golemPort: Int,
    golemTrustId: String,
    version: String,
) {
    fun buildPairingResponse(): PairingInfoResponse {
        val lanHost = detectLanIp()
        return PairingInfoResponse(
            host = lanHost,
            port = golemPort,
            token = golemPairingToken,
            trustId = golemTrustId,
            version = version,
        )
    }

    get("/pairing/info") {
        val providedToken = call.request.headers["X-Pairing-Token"]
        if (golemPairingToken.isNotBlank() && providedToken != golemPairingToken) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "invalid pairing token"))
            return@get
        }
        call.respond(buildPairingResponse())
    }

    get("/pairing/qrcode") {
        val providedToken = call.request.headers["X-Pairing-Token"]
        if (golemPairingToken.isNotBlank() && providedToken != golemPairingToken) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "invalid pairing token"))
            return@get
        }
        val infoJson = Json.encodeToString(buildPairingResponse())
        val pngBytes = generateQrCode(infoJson)
        call.response.header("Content-Disposition", "inline; filename=\"pairing-qrcode.png\"")
        call.respondBytes(pngBytes, ContentType.Image.PNG)
    }
}
