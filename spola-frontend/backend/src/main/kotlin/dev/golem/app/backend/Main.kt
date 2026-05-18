package dev.spola.app.backend

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import dev.spola.app.backend.routes.*
import dev.spola.app.db.OpenClawDb
import dev.spola.app.models.OpenClawOptions
import dev.spola.app.models.PairingInfo
import dev.spola.app.models.TrustState
import dev.spola.app.state.AppStateStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.sql.DriverManager
import java.util.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import java.io.File

private const val DB_URL = "jdbc:sqlite:openclaw.db"
private const val DEFAULT_BACKEND_PORT = 9090
private const val BACKEND_PORT_SCAN_WINDOW = 25

val ollamaClient = HttpClient(OkHttp) {
    install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
    install(io.ktor.client.plugins.HttpTimeout) {
        requestTimeoutMillis = 600000 // 10 minutes
        connectTimeoutMillis = 20000  // 20 seconds
        socketTimeoutMillis = 600000  // 10 minutes
    }
}

internal fun pickBackendPort(
    preferredPort: Int,
    scanWindow: Int = BACKEND_PORT_SCAN_WINDOW,
    isAvailable: (Int) -> Boolean = ::isTcpPortAvailable,
): Int {
    val start = preferredPort.coerceIn(1, 65535)
    val candidates = buildList {
        add(start)
        for (offset in 1..scanWindow) {
            val candidate = start + offset
            if (candidate in 1..65535) add(candidate)
        }
    }
    return candidates.firstOrNull(isAvailable)
        ?: error("No free TCP port available in range ${candidates.first()}..${candidates.last()}")
}

private fun isTcpPortAvailable(port: Int): Boolean {
    return runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port))
        }
    }.isSuccess
}

@Serializable
data class McpRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class McpResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonElement? = null
)

private fun mcpMain() {
    val json = Json { ignoreUnknownKeys = true }
    val reader = System.`in`.bufferedReader()

    while (true) {
        val line = reader.readLine() ?: break
        runCatching {
            val req = json.decodeFromString<McpRequest>(line)
            val response = when (req.method) {
                "initialize" -> handleInitialize(req, json)
                "list_tools", "tools/list" -> handleListTools(req, json)
                "call_tool", "tools/call" -> handleCallTool(req, json)
                else -> null
            }
            if (response != null) println(response)
        }
    }
}

private fun handleInitialize(req: McpRequest, json: Json): String {
    val response = McpResponse(id = req.id, result = buildJsonObject {
        put("protocolVersion", "2024-11-05")
        put("capabilities", buildJsonObject {})
        put("serverInfo", buildJsonObject {
            put("name", "openclaw-app")
            put("version", "1.0.0")
        })
    })
    return json.encodeToString(response)
}

private fun handleListTools(req: McpRequest, json: Json): String {
    val response = McpResponse(id = req.id, result = buildJsonObject {
        put("tools", buildJsonArray {
            add(buildJsonObject {
                put("name", "read_session_file")
                put("description", "Read the content of a file uploaded to the current session.")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("sessionId", buildJsonObject { put("type", "string") })
                        put("fileId", buildJsonObject { put("type", "string") })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("sessionId"))
                        add(JsonPrimitive("fileId"))
                    })
                })
            })
        })
    })
    return json.encodeToString(response)
}

private fun handleCallTool(req: McpRequest, json: Json): String? {
    val params = req.params ?: buildJsonObject {}
    val name = params["name"]?.jsonPrimitive?.content
    val toolParams = params["arguments"]?.jsonObject ?: buildJsonObject {}

    if (name != "read_session_file") {
        return null
    }

    val sessionId = toolParams["sessionId"]?.jsonPrimitive?.content
    val fileId = toolParams["fileId"]?.jsonPrimitive?.content
    if (sessionId == null || fileId == null) {
        return null
    }

    val uploadsRoot = File(System.getProperty("user.home"), ".openclaw/uploads")
    val file = File(File(uploadsRoot, sessionId), fileId)
    val content = if (file.exists() && file.isFile) {
        file.readText()
    } else {
        "Error: File not found at ${file.absolutePath}"
    }

    val response = McpResponse(id = req.id, result = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", content)
            })
        })
    })
    return json.encodeToString(response)
}

fun main(args: Array<String>) {
    if (args.contains("mcp")) {
        mcpMain()
        return
    }
    val driver = JdbcSqliteDriver(DB_URL)
    // Initialize schema
    try {
        OpenClawDb.Schema.create(driver)
    } catch (e: Exception) {
        println("[DB] Schema already exists or creation failed: ${e.message}. Attempting migrations...")
        // Simple migration for missing columns
        val connection = DriverManager.getConnection(DB_URL)
        runCatching {
            val stmt = connection.createStatement()
            stmt.execute("ALTER TABLE ChatSessionEntity ADD COLUMN providerId TEXT NOT NULL DEFAULT 'ollama';")
            println("[DB] Added providerId to ChatSessionEntity")
        }
        runCatching {
            val stmt = connection.createStatement()
            stmt.execute("ALTER TABLE MessageEntity ADD COLUMN attachments TEXT;")
            println("[DB] Added attachments to MessageEntity")
        }
        runCatching {
            val stmt = connection.createStatement()
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS FileEntity (
                    id TEXT PRIMARY KEY NOT NULL,
                    sessionId TEXT NOT NULL,
                    name TEXT NOT NULL,
                    mimeType TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    storagePath TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    FOREIGN KEY (sessionId) REFERENCES ChatSessionEntity(id)
                );
            """.trimIndent())
            println("[DB] Ensured FileEntity table exists")
        }
        connection.close()
    }
    
    val db = OpenClawDb(driver)
    val stateStore = AppStateStore(db)

    val hostIp = InetAddress.getLocalHost().hostAddress
    val existing = stateStore.loadTrustedHost()?.takeIf { it.active }
    val preferredPort = (
        System.getProperty("openclaw.port")
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: existing?.port
            ?: DEFAULT_BACKEND_PORT
        )
    val serverPort = pickBackendPort(preferredPort)
    if (serverPort != preferredPort) {
        println("Port $preferredPort is busy; using $serverPort.")
    }

    val bootstrapTrust = (existing ?: TrustState(
        host = hostIp,
        port = serverPort,
        token = UUID.randomUUID().toString(),
        trustId = UUID.randomUUID().toString(),
        active = true,
    )).copy(
        host = hostIp,
        port = serverPort,
        active = true,
        revokedAt = null,
    ).also(stateStore::saveTrustedHost)

    val pairingPayload = PairingInfo(
        host = bootstrapTrust.host,
        port = bootstrapTrust.port,
        token = bootstrapTrust.token,
        trustId = bootstrapTrust.trustId,
    )

    println(
        "\n=== OPENCLAW PAIRING INFO ===\n" +
            "Payload: ${Json.encodeToString(PairingInfo.serializer(), pairingPayload)}\n" +
            "=============================\n"
    )

    embeddedServer(Netty, port = serverPort, host = "0.0.0.0") { module(db) }.start(wait = true)
}

fun Application.module(db: OpenClawDb, servicesOverride: BackendServices? = null) {
    val services = servicesOverride ?: BackendServices(db, ollamaClient)

    install(ServerContentNegotiation) { json(Json { prettyPrint = true; isLenient = true; ignoreUnknownKeys = true }) }
    install(SSE)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is io.ktor.util.cio.ChannelWriteException || cause is java.io.IOException) {
                // Client disconnected, ignore
                return@exception
            }
            val path = call.request.path()
            println("Unhandled server exception on $path: ${cause.message}")
            cause.printStackTrace()
            when (path) {
                "/models" -> call.respondText("[]", ContentType.Application.Json, HttpStatusCode.OK)
                "/openclaw/options" -> call.respondText(
                    Json.encodeToString(OpenClawOptions.serializer(), OpenClawOptions()),
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
                else -> call.respond(HttpStatusCode.InternalServerError, "Internal Server Error")
            }
        }
    }

    routing {
        get("/") {
            call.respondText("OpenClaw Running")
        }

        trustRoutes(services)
        auditRoutes(services)
        modelRoutes(services)
        sessionRoutes(services)
        bashRoutes(services)
        fileRoutes(services)
        speechRoutes(services)
    }
}
