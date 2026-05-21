package dev.spola.app.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import dev.spola.app.app.decompose.DefaultRootComponent

fun main() {
    System.setProperty("skiko.renderApi", "SOFTWARE")
    System.setProperty("swing.bufferPerWindow", "false")
    System.setProperty("sun.java2d.opengl", "false")
    System.setProperty("sun.java2d.xrender", "false")
    System.setProperty("sun.awt.noerasebackground", "true")
    System.setProperty("sun.awt.enableExtraMouseButtons", "true")
    System.setProperty("awt.toolkit", "sun.awt.X11.XToolkit")

    application {
        val lifecycle = remember { LifecycleRegistry() }
        val root = remember { DefaultRootComponent(DefaultComponentContext(lifecycle)) }

        Window(
            onCloseRequest = ::exitApplication,
            title = "Spola",
            state = rememberWindowState(
                size = DpSize(1280.dp, 900.dp),
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                App(root)
            }
        }
    }
}
