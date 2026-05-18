package com.example

fun main() {
    val words = listOf("hello", "racecar", "world", "level")

    for (word in words) {
        val capitalized = StringUtils.capitalize(word)
        val reversed = StringUtils.reverse(word)
        val palindrome = StringUtils.isPalindrome(word)

        println("$word -> capitalized: $capitalized, reversed: $reversed, palindrome: $palindrome")
    }
}
