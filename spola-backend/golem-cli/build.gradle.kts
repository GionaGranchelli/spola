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
    implementation(project(":golem-core"))
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
    mainClass = "dev.golem.cli.MainKt"
}

// Build a runnable distribution: ./gradlew installDist
// Then run: ./golem-cli/build/install/golem-cli/bin/golem-cli
