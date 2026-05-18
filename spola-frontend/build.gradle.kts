plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.buildkonfig) apply false
    alias(libs.plugins.ktor) apply false
    id("org.sonarqube") version "5.1.0.4882"
}

sonarqube {
    properties {
        property("sonar.host.url", "http://localhost:9000")
        property("sonar.token", System.getenv("SONAR_TOKEN") ?: "")
        property("sonar.projectKey", "openclaw-app")
        property("sonar.projectName", "OpenClaw App")
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.exclusions", "**/fixtures/**")
    }
}
