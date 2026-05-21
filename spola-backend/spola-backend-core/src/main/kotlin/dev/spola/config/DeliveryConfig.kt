package dev.spola.config

data class DeliveryConfig(
    val emailEnabled: Boolean = false,
    val smtpHost: String = "",
    val smtpPort: Int = 587,
    val smtpUser: String = "",
    val smtpPass: String = "",
    val fromEmail: String = "",
    val telegramToken: String = "",
    val telegramChatId: String = "",
    val discordToken: String = "",
    val discordEnabled: Boolean = false, // Reserved for future feature toggle
)
