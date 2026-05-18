package dev.spola.app.app.theme

import androidx.compose.ui.graphics.Color

object GolemColors {
    // ── Dark Mode ──────────────────────────────────────────────────────
    object Dark {
        // Background
        val bg = Color(0xFF0D0D0D)
        val bgSurface = Color(0xFF1A1A1A)
        val bgElevated = Color(0xFF242424)

        // Text
        val textPrimary = Color(0xFFE8E8E8)
        val textSecondary = Color(0xFFA0A0A0)
        val textMuted = Color(0xFF6B6B6B)

        // Accent
        val accent = Color(0xFF6C63FF)
        val accentLight = Color(0xFF8B83FF)

        // Semantic
        val success = Color(0xFF34C759)
        val warning = Color(0xFFE8A728)
        val error = Color(0xFFFF453A)

        // Chat bubbles
        val userBubble = Color(0xFF6C63FF)
        val userBubbleText = Color(0xFFFFFFFF)
        val assistantBubble = Color(0xFF2C2C2E)
        val assistantBubbleText = Color(0xFFE8E8E8)
        val codeBlock = Color(0xFF1E1E1E)

        // Tab bar
        val tabBg = Color(0xFF1A1A1A)
        val tabInactive = Color(0xFF6B6B6B)
        val tabActive = Color(0xFF6C63FF)

        // Tool timeline
        val thinkingBg = Color(0xFF2C2C2E)
        val toolCallBg = Color(0xFF1E1E2E)
        val toolSuccess = Color(0xFF34C759)
        val toolError = Color(0xFFFF453A)
        val toolRunning = Color(0xFF8B83FF)
    }

    // ── Light Mode ─────────────────────────────────────────────────────
    object Light {
        // Background
        val bg = Color(0xFFF5F5F5)
        val bgSurface = Color(0xFFFFFFFF)
        val bgElevated = Color(0xFFE8E8E8)

        // Text
        val textPrimary = Color(0xFF1A1A1A)
        val textSecondary = Color(0xFF5A5A5A)
        val textMuted = Color(0xFF6E6E6E)   // 4.56:1 on bg (#F5F5F5) — WCAG AA ✅

        // Accent
        val accent = Color(0xFF6C63FF)
        val accentLight = Color(0xFF8B83FF)

        // Semantic
        val success = Color(0xFF248A3D)
        val warning = Color(0xFFB8860B)
        val error = Color(0xFFD32F2F)

        // Chat bubbles
        val userBubble = Color(0xFF6C63FF)
        val userBubbleText = Color(0xFFFFFFFF)
        val assistantBubble = Color(0xFFE8E8EC)
        val assistantBubbleText = Color(0xFF1A1A1A)
        val codeBlock = Color(0xFFF0F0F0)

        // Tab bar
        val tabBg = Color(0xFFFFFFFF)
        val tabInactive = Color(0xFF8A8A8A)
        val tabActive = Color(0xFF6C63FF)

        // Tool timeline
        val thinkingBg = Color(0xFFE8E8EC)
        val toolCallBg = Color(0xFFEEEEFF)
        val toolSuccess = Color(0xFF248A3D)
        val toolError = Color(0xFFD32F2F)
        val toolRunning = Color(0xFF6C63FF)
    }

    // ── Current (default to Dark) ──────────────────────────────────────
    var isDarkMode: Boolean = true

    // Background
    val bg: Color get() = if (isDarkMode) Dark.bg else Light.bg
    val bgSurface: Color get() = if (isDarkMode) Dark.bgSurface else Light.bgSurface
    val bgElevated: Color get() = if (isDarkMode) Dark.bgElevated else Light.bgElevated

    // Text
    val textPrimary: Color get() = if (isDarkMode) Dark.textPrimary else Light.textPrimary
    val textSecondary: Color get() = if (isDarkMode) Dark.textSecondary else Light.textSecondary
    val textMuted: Color get() = if (isDarkMode) Dark.textMuted else Light.textMuted

    // Accent
    val accent: Color get() = if (isDarkMode) Dark.accent else Light.accent
    val accentLight: Color get() = if (isDarkMode) Dark.accentLight else Light.accentLight

    // Semantic
    val success: Color get() = if (isDarkMode) Dark.success else Light.success
    val warning: Color get() = if (isDarkMode) Dark.warning else Light.warning
    val error: Color get() = if (isDarkMode) Dark.error else Light.error

    // Chat bubbles
    val userBubble: Color get() = if (isDarkMode) Dark.userBubble else Light.userBubble
    val userBubbleText: Color get() = if (isDarkMode) Dark.userBubbleText else Light.userBubbleText
    val assistantBubble: Color get() = if (isDarkMode) Dark.assistantBubble else Light.assistantBubble
    val assistantBubbleText: Color get() = if (isDarkMode) Dark.assistantBubbleText else Light.assistantBubbleText
    val codeBlock: Color get() = if (isDarkMode) Dark.codeBlock else Light.codeBlock

    // Tab bar
    val tabBg: Color get() = if (isDarkMode) Dark.tabBg else Light.tabBg
    val tabInactive: Color get() = if (isDarkMode) Dark.tabInactive else Light.tabInactive
    val tabActive: Color get() = if (isDarkMode) Dark.tabActive else Light.tabActive

    // Tool timeline
    val thinkingBg: Color get() = if (isDarkMode) Dark.thinkingBg else Light.thinkingBg
    val toolCallBg: Color get() = if (isDarkMode) Dark.toolCallBg else Light.toolCallBg
    val toolSuccess: Color get() = if (isDarkMode) Dark.toolSuccess else Light.toolSuccess
    val toolError: Color get() = if (isDarkMode) Dark.toolError else Light.toolError
    val toolRunning: Color get() = if (isDarkMode) Dark.toolRunning else Light.toolRunning
}
