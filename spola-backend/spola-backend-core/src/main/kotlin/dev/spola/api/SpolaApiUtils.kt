package dev.spola.api

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import dev.spola.NotFoundException
import dev.spola.ValidationException
import io.ktor.server.application.ApplicationCall
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import javax.imageio.ImageIO

/**
 * Detects the machine's LAN IP address.
 * First tries to find the interface matching the default route (via reading /proc/net/route).
 * Falls back to iterating non-loopback, non-virtual IPv4 interfaces.
 * Skips Docker/bridge/virtual interfaces (docker, br-, veth, etc.).
 * Falls back to "localhost".
 */
fun detectLanIp(): String {
    // Strategy 1: Parse /proc/net/route to find the interface with the default gateway.
    try {
        val defaultGwIface = java.io.File("/proc/net/route").useLines { lines ->
            lines.drop(1) // skip header
                .map { it.split('\t') }
                .firstOrNull { parts -> parts.size > 1 && parts[1] == "00000000" }
                ?.getOrNull(0)
        }
        if (defaultGwIface != null) {
            val ni = NetworkInterface.getByName(defaultGwIface)
            if (ni != null && !ni.isLoopback && ni.isUp) {
                val addr = ni.inetAddresses.asSequence()
                    .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
                if (addr != null) return addr.hostAddress
            }
        }
    } catch (_: Exception) {
        // Fall through to strategy 2
    }

    // Strategy 2: Iterate interfaces, skip loopback/down/docker/bridge/virtual.
    val virtualPrefixes = listOf("docker", "br-", "veth", "vbox", "vmnet", "tailscale")
    val interfaces = NetworkInterface.getNetworkInterfaces().asSequence()
    for (ni in interfaces) {
        if (ni.isLoopback || !ni.isUp) continue
        val name = ni.name.lowercase()
        if (virtualPrefixes.any { name.startsWith(it) }) continue
        val addr = ni.inetAddresses.asSequence()
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
        if (addr != null) return addr.hostAddress
    }
    return "localhost"
}

/**
 * Generates a QR code PNG image bytes from the given text.
 */
fun generateQrCode(text: String, size: Int = 300): ByteArray {
    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
    val image = MatrixToImageWriter.toBufferedImage(bitMatrix)
    val baos = ByteArrayOutputStream()
    ImageIO.write(image, "png", baos)
    return baos.toByteArray()
}

fun ApplicationCall.requirePathParameter(name: String, label: String = name): String =
    parameters[name] ?: throw ValidationException("missing $label")

fun String.toRequiredLong(label: String): Long =
    toLongOrNull() ?: throw ValidationException("invalid $label: $this")

inline fun <T> T?.orNotFound(message: () -> String): T =
    this ?: throw NotFoundException(message())
