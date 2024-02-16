package fr.acinq.lightning.bin.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.acinq.phoenix.db.ChannelsDatabase

fun init(): SqlDriver {
    val driver = JdbcSqliteDriver("jdbc:sqlite:phoenix.db")
    ChannelsDatabase.Schema.create(driver)
    return driver
}

actual val createAppDbDriver: SqlDriver
    get() = init()