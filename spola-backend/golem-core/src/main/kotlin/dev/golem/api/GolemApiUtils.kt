package dev.spola.api

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import javax.imageio.ImageIO

/**
 * Detects the machine's LAN IP address by iterating network interfaces.
 * Prefers IPv4, non-loopback, active interfaces. Falls back to "localhost".
 */
fun detectLanIp(): String {
    val interfaces = NetworkInterface.getNetworkInterfaces().asSequence()
    for (ni in interfaces) {
        if (ni.isLoopback || !ni.isUp) continue
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
