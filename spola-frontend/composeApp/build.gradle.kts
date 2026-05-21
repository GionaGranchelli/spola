plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }
    
    jvm("desktop")
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.decompose)
            implementation(libs.decompose.extensions.compose)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlin.reflect)
        }
        
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
        }
        
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.logback.classic)
            }
        }
        
        iosMain.dependencies {
        }
    }
}

android {
    namespace = "dev.spola.app"
    compileSdk = 35
    
    defaultConfig {
        applicationId = "dev.spola.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "0.0.7"
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

compose.desktop {
    application {
        mainClass = "dev.spola.app.app.MainKt"
        
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageName = "Spola"
            packageVersion = "1.0.0"
        }
    }
}
