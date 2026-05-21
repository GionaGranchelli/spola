package dev.spola.app.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.arkivanov.decompose.defaultComponentContext
import dev.spola.app.app.decompose.DefaultRootComponent
import dev.spola.app.db.DriverFactory
import dev.spola.app.db.SpolaDb

class MainActivity : ComponentActivity() {
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        AndroidFilePickerRegistry.onResult(this, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        initAppContext(this)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            android.util.Log.e("Spola Client", "Uncaught crash", throwable)
        }

        AndroidFilePickerRegistry.setLauncher {
            pickFileLauncher.launch("*/*")
        }

        val db = try {
            SpolaDb(DriverFactory(this).createDriver())
        } catch (e: Exception) {
            android.util.Log.e("Spola Client", "DB init failed, running without persistence", e)
            null
        }
        val root = DefaultRootComponent(defaultComponentContext(), db)
        setContent {
            App(root)
        }
    }
}
