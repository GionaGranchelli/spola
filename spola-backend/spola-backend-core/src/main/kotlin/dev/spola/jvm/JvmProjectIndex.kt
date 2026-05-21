package dev.spola.jvm

interface JvmProjectIndex {
    suspend fun scan(projectDir: String): JvmProjectSnapshot
    suspend fun getSnapshot(): JvmProjectSnapshot?
    suspend fun clear()
}
