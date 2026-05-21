plugins {
    kotlin("jvm") version "2.1.0"
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":lib"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}
