package dev.spola.cli

import dev.spola.api.detectLanIp
import dev.spola.api.generateQrCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Path
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Callable

@Command(
    name = "pairing",
    description = ["Manage Golem pairing configuration"],
    subcommands = [PairingInfoCommand::class, PairingQrCodeCommand::class],
)
class PairingCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: GolemCli

    override fun call(): Int {
        CommandLine.usage(this, System.out)
        return 0
    }
}

private fun buildPairingCliInfo(root: GolemCli): PairingCliInfo {
    val resolvedApiKey = root.apiKey ?: System.getenv("GOLEM_API_KEY")
    val token = resolvedApiKey ?: UUID.randomUUID().toString()
    val host = detectLanIp()
    val trustId = UUID.randomUUID().toString()
    return PairingCliInfo(
        host = host,
        port = root.apiPort,
        token = token,
        trustId = trustId,
        version = "0.1.0",
    )
}

@Command(name = "info", description = ["Print connection JSON for pairing"])
class PairingInfoCommand : Callable<Int> {
    @ParentCommand
    lateinit var pairingCommand: PairingCommand

    override fun call(): Int {
        val info = buildPairingCliInfo(pairingCommand.root)
        val jsonText = Json { prettyPrint = true }.encodeToString(PairingCliInfo.serializer(), info)

        println("=== Golem Connection Info ===")
        println("Host: ${info.host}")
        println("Port: ${info.port}")
        println("Token: ${info.token}")
        println("Trust ID: ${info.trustId}")
        println()
        println("Paste this JSON into the OpenClaw app:")
        println(jsonText)
        println()
        println("Or run 'golem pairing qrcode' to generate a QR code image.")
        return 0
    }
}

@Command(name = "qrcode", description = ["Save pairing QR code to qrcode.png"])
class PairingQrCodeCommand : Callable<Int> {
    @ParentCommand
    lateinit var pairingCommand: PairingCommand

    @Option(
        names = ["--output", "-o"],
        description = ["Output file path (default: qrcode.png)"],
        defaultValue = "qrcode.png",
    )
    var outputPath: String = "qrcode.png"

    override fun call(): Int {
        val info = buildPairingCliInfo(pairingCommand.root)
        val jsonText = Json { prettyPrint = true }.encodeToString(PairingCliInfo.serializer(), info)

        val pngBytes = generateQrCode(jsonText)
        val path = Path.of(outputPath)
        Files.write(path, pngBytes)
        println("QR code saved to ${path.toAbsolutePath()}")
        println("Open the OpenClaw app and scan this QR code to pair.")
        return 0
    }
}
