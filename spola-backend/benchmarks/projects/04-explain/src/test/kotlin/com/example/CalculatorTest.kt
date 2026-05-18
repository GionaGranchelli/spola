package com.example

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class CalculatorTest {

    private val calculator = Calculator()

    @Test
    fun testAdd() {
        // BUG: This test calls subtract() instead of add()
        // Expected: 2 + 3 = 5
        // Actual: 2 - 3 = -1
        assertEquals(5, calculator.subtract(2, 3))
    }
}
