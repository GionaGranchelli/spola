# Compose Multiplatform + KMP ProGuard rules
# Keep Compose runtime
-keep class androidx.compose.** { *; }
-keep class * extends androidx.compose.runtime.Composable { *; }

# Keep kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.golem.**$$serializer { *; }
-keepclassmembers class dev.golem.** { *** Companion; }
-keepclasseswithmembers class dev.golem.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep Decompose
-keep class com.arkivanov.decompose.** { *; }

# Keep Ktor
-keep class io.ktor.** { *; }

# Keep SQLDelight
-keep class app.cash.sqldelight.** { *; }

# Keep coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
