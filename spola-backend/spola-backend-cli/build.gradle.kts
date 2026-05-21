plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = rootProject.group
version = rootProject.version

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":spola-backend-core"))
    implementation(platform(libs.tramai.bom))
    implementation(libs.tramai.orchestration)
    implementation(libs.picocli)
    implementation(libs.coroutines.core)
    implementation(libs.logback.classic)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jline:jline:3.27.1")
    implementation("org.jline:jline-terminal-jna:3.27.1")

    testImplementation(libs.kotlin.test)
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(libs.assertj.core)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass = "dev.spola.cli.MainKt"
    applicationName = "spola"
}

// Build a runnable distribution: ./gradlew installDist
// Then run: ./spola-backend-cli/build/install/spola/bin/spola
