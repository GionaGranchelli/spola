package com.example

class App {
    val name: String = "app"
    var count: Int = 0

    fun greet(target: String): String {
        return "Hello, $target"
    }
}

fun main() {
    println(App().greet("world"))
}
