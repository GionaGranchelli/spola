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
    spolaPairingToken: String,
    spolaPort: Int,
    spolaTrustId: String,
    version: String,
) {
    fun buildPairingResponse(): PairingInfoResponse {
        val lanHost = detectLanIp()
        return PairingInfoResponse(
            host = lanHost,
            port = spolaPort,
            token = spolaPairingToken,
            trustId = spolaTrustId,
            version = version,
        )
    }

    get("/pairing/info") {
        // Pairing info is the entry point — no auth required.
        // The returned pairing token is used by the client for subsequent calls.
        call.respond(buildPairingResponse())
    }

    get("/pairing/qrcode") {
        // QR code is also open — the client scans it to get the pairing info including token.
        val infoJson = Json.encodeToString(buildPairingResponse())
        val pngBytes = generateQrCode(infoJson)
        call.response.header("Content-Disposition", "inline; filename=\"pairing-qrcode.png\"")
        call.respondBytes(pngBytes, ContentType.Image.PNG)
    }
}
