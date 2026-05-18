plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "com.example"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":api"))
    implementation(project(":core"))
}

application {
    mainClass.set("com.example.app.MainKt")
}
