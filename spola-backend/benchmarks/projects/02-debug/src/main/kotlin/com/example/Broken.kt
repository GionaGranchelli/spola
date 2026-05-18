package com.example

import java.io.File

fun main() {
    val result = calculateTotal(listOf(1, 2, 3, "4", 5))
    println("Total: $result")

    val path = "/tmp/test.txt"
    val content = readFileContent(path)
    println("Content: $content")
}

fun calculateTotal(items: List<Any>): Int {
    return items.sumOf { it as Int }
}

// BUG: This function has a type mismatch - it returns String? but declares Int
fun readFileContent(path: String): Int {
    val file = File(path)
    if (file.exists()) {
        return file.readText()
    }
    return "File not found"
}
