package dev.spola.tools

import dev.spola.GolemConfig
import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties

/**
 * Register delivery tools: telegram_send and email_send.
 * These tools require configuration via [GolemConfig] or environment variables.
 */
fun registerDeliveryTools(
    registry: ToolRegistry,
    config: GolemConfig = GolemConfig(),
) {
    registry.register(telegramSendTool(config))
    registry.register(emailSendTool(config))
}

private fun telegramSendTool(config: GolemConfig): Tool {
    val token = resolveTelegramToken(config)
    return Tool(
        name = "telegram_send",
        description = "Send a message via Telegram Bot API to a specified chat or channel. Requires TELEGRAM_BOT_TOKEN env var or config.",
        parameters = listOf(
            ToolParameter("chat_id", "Telegram chat ID (numeric or @channelusername)", ToolParameterType.STRING),
            ToolParameter("text", "Message text to send (max 4096 characters)", ToolParameterType.STRING),
        ),
        execute = { args ->
            try {
                val chatId = (args["chat_id"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: chat_id")
                if (chatId.isEmpty()) return@Tool ToolResult.fail("chat_id must not be empty")

                val text = (args["text"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: text")
                if (text.isEmpty()) return@Tool ToolResult.fail("text must not be empty")
                if (text.length > 4096) return@Tool ToolResult.fail("Message too long: ${text.length} chars (max 4096)")

                if (token == null) return@Tool ToolResult.fail(
                    "Telegram bot token not configured. Set TELEGRAM_BOT_TOKEN env var or configure telegramBotToken in GolemConfig."
                )

                val url = "https://api.telegram.org/bot$token/sendMessage"
                val jsonBody = """{"chat_id":"$chatId","text":"${escapeJson(text)}","parse_mode":"Markdown"}"""

                val client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                when {
                    response.statusCode() in 200..299 -> {
                        ToolResult.ok("Telegram message sent successfully (chat_id=$chatId, HTTP ${response.statusCode()})")
                    }
                    response.statusCode() == 401 -> {
                        ToolResult.fail("Telegram API returned 401: Unauthorized — check bot token")
                    }
                    response.statusCode() == 400 -> {
                        val body = response.body().take(500)
                        ToolResult.fail("Telegram API returned 400: Bad request — $body")
                    }
                    else -> {
                        ToolResult.fail("Telegram API returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
                    }
                }
            } catch (e: java.net.http.HttpTimeoutException) {
                ToolResult.fail("Telegram API request timed out: ${e.message}")
            } catch (e: java.net.ConnectException) {
                ToolResult.fail("Could not connect to Telegram API: ${e.message}")
            } catch (e: Exception) {
                ToolResult.fail("Failed to send Telegram message: ${e.message}")
            }
        },
    )
}

private fun emailSendTool(config: GolemConfig): Tool {
    val (host, port, username, password, from) = resolveEmailConfig(config)

    return Tool(
        name = "email_send",
        description = "Send an email via SMTP. Requires SMTP host, port, username, password, and from address in config or env vars.",
        parameters = listOf(
            ToolParameter("to", "Recipient email address", ToolParameterType.STRING),
            ToolParameter("subject", "Email subject line", ToolParameterType.STRING),
            ToolParameter("body", "Email body text (plain text)", ToolParameterType.STRING),
        ),
        execute = { args ->
            try {
                val to = (args["to"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: to")
                if (to.isEmpty()) return@Tool ToolResult.fail("to must not be empty")
                if (!to.contains("@")) return@Tool ToolResult.fail("Invalid email address: $to")

                val subject = (args["subject"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: subject")
                val body = (args["body"] as? String)
                    ?: return@Tool ToolResult.fail("Missing required argument: body")

                if (host == null || host.isBlank()) return@Tool ToolResult.fail(
                    "SMTP host not configured. Set EMAIL_SMTP_HOST env var or configure emailSmtpHost in GolemConfig."
                )
                if (username == null || password == null) return@Tool ToolResult.fail(
                    "SMTP credentials not configured. Set EMAIL_USERNAME and EMAIL_PASSWORD env vars or configure in GolemConfig."
                )
                if (from == null || from.isBlank()) return@Tool ToolResult.fail(
                    "SMTP from address not configured. Set EMAIL_FROM env var or configure emailFrom in GolemConfig."
                )

                val props = Properties().apply {
                    put("mail.smtp.host", host)
                    put("mail.smtp.port", port.toString())
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.connectiontimeout", "10000")
                    put("mail.smtp.timeout", "15000")
                }

                val session = Session.getInstance(props, null)
                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(from))
                    setRecipient(Message.RecipientType.TO, InternetAddress(to))
                    setSubject(subject)
                    setText(body)
                }

                Transport.send(message, username, password)
                ToolResult.ok("Email sent successfully to $to (subject: $subject)")
            } catch (e: jakarta.mail.MessagingException) {
                val causeMsg = e.cause?.message?.let { ": $it" } ?: ""
                ToolResult.fail("Failed to send email: ${e.message}$causeMsg")
            } catch (e: Exception) {
                ToolResult.fail("Failed to send email: ${e.message}")
            }
        },
    )
}

private fun resolveTelegramToken(config: GolemConfig): String? {
    return config.telegramBotToken
        ?: System.getenv("TELEGRAM_BOT_TOKEN")
        ?.takeIf { it.isNotBlank() }
}

private data class EmailConfig(
    val host: String?,
    val port: Int,
    val username: String?,
    val password: String?,
    val from: String?,
)

private fun resolveEmailConfig(config: GolemConfig): EmailConfig {
    return EmailConfig(
        host = config.emailSmtpHost ?: System.getenv("EMAIL_SMTP_HOST")?.takeIf { it.isNotBlank() },
        port = config.emailSmtpPort,
        username = config.emailUsername ?: System.getenv("EMAIL_USERNAME")?.takeIf { it.isNotBlank() },
        password = config.emailPassword ?: System.getenv("EMAIL_PASSWORD")?.takeIf { it.isNotBlank() },
        from = config.emailFrom ?: System.getenv("EMAIL_FROM")?.takeIf { it.isNotBlank() },
    )
}

/**
 * Escape special characters in a JSON string value.
 */
private fun escapeJson(s: String): String {
    return s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
