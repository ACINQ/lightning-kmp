package fr.acinq.lightning.bin

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.acinq.phoenix.db.ChannelsDatabase
import kotlinx.io.files.Path

actual val homeDirectory: Path = Path(System.getProperty("user.home"))

actual fun createAppDbDriver(dir: Path): SqlDriver {
    val path = Path(dir, "phoenix.db")
    val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    ChannelsDatabase.Schema.create(driver)
    return driver
}