pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "simple-multi-module"
include(":app")
include(":lib")
