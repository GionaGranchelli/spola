plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}
