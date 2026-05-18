package com.example.lib

public interface Formatter {
    fun format(value: String): String
}

internal object Utils {
    fun normalize(
        value: String,
    ): String {
        return value.trim().lowercase()
    }
}

enum class Mode {
    FAST,
    SAFE,
}

class Helper : Formatter {
    override fun format(value: String): String = Utils.normalize(value)
}
