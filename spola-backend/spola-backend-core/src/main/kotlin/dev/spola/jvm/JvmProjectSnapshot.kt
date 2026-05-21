package dev.spola.jvm

import kotlinx.serialization.Serializable

@Serializable
data class JvmProjectSnapshot(
    val projectDir: String,
    val scannedAt: Long,
    val modules: List<ProjectModule>,
)

@Serializable
data class ProjectModule(
    val name: String,
    val path: String,
    val isRoot: Boolean,
    val sourceDirs: List<String>,
    val testDirs: List<String>,
    val plugins: List<String>,
    val javaVersion: String?,
    val kotlinVersion: String?,
    val dependencies: List<String>,
)
