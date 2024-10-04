package fr.acinq.lightning.db.sqlite

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.*

actual fun createSqlDriver(): SqlDriver = JdbcSqliteDriver("jdbc:sqlite::memory:", Properties(), SqlitePaymentsDb.Schema)