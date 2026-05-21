plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = rootProject.group
version = rootProject.version

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform(libs.tramai.bom))
    implementation(libs.tramai.standalone)
    implementation(libs.tramai.openai)
    implementation(libs.tramai.anthropic)
    implementation(libs.tramai.core)
    implementation(libs.tramai.scheduler)
    implementation(libs.tramai.observability)
    implementation(libs.tramai.orchestration)
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.sdk.trace)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.coroutines.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.sqlite.jdbc)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.logback.classic)
    implementation(libs.mcp.sdk.server)
    implementation(libs.mcp.sdk.client)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.okio)
    implementation(libs.prometheus.simpleclient)
    implementation(libs.angus.mail)
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)

    testImplementation(libs.kotlin.test)
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(libs.coroutines.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
}

tasks.test {
    useJUnitPlatform()
}
