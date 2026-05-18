package com.example

import org.junit.Test
import org.junit.Assert.assertEquals

class MyTest {

    private val calculator = Calculator()

    @Test
    fun testAddition() {
        assertEquals(5, calculator.add(2, 3))
    }

    @Test
    fun testSubtraction() {
        assertEquals(3, calculator.subtract(10, 7))
    }

    @Test
    fun testMultiplication() {
        assertEquals(20, calculator.multiply(4, 5))
    }
}
