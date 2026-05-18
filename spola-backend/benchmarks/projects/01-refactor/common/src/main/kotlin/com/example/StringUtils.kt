package com.example

object StringUtils {
    fun capitalize(text: String): String {
        return text.replaceFirstChar { it.uppercase() }
    }

    fun reverse(text: String): String {
        return text.reversed()
    }

    fun isPalindrome(text: String): Boolean {
        val cleaned = text.filter { it.isLetterOrDigit() }.lowercase()
        return cleaned == cleaned.reversed()
    }
}
