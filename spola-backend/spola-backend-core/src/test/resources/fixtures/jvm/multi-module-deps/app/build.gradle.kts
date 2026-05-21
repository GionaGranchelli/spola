plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":service"))
    testImplementation(project(":api"))
}
