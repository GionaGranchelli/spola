package com.example

import kotlin.test.Test
import kotlin.test.assertEquals

class AppTest {
    @Test
    fun greet() {
        assertEquals("Hello, test", App().greet("test"))
    }
}
