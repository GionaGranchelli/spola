package dev.spola.app.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.spola.app.db.SpolaDb

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(SpolaDb.Schema, context, "spola.db")
    }
}
