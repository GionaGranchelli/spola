plugins {
    base
    id("org.sonarqube") version "5.1.0.4882"
}

group = "dev.spola"
version = "0.1.1"

sonarqube {
    properties {
        property("sonar.host.url", "http://localhost:9000")
        property("sonar.token", System.getenv("SONAR_TOKEN") ?: "")
        property("sonar.projectKey", "spola-backend")
        property("sonar.projectName", "Spola Backend")
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.exclusions", "**/examples/**")
    }
}
