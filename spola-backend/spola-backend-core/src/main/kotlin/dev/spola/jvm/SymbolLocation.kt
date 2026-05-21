package dev.spola.jvm

import kotlinx.serialization.Serializable

@Serializable
data class SymbolLocation(
    val name: String,
    val kind: SymbolKind,
    val file: String,
    val line: Int,
    val column: Int,
    val module: String,
    val visibility: String? = null,
)

@Serializable
enum class SymbolKind {
    CLASS,
    INTERFACE,
    OBJECT,
    FUN,
    VAL,
    VAR,
    ENUM,
    ANNOTATION,
}
