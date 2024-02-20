package fr.acinq.lightning.bin

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import fr.acinq.phoenix.db.ChannelsDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual val homeDirectory: Path = Path(getenv("HOME")?.toKString()!!)

actual fun createAppDbDriver(dir: Path): SqlDriver {
    return NativeSqliteDriver(ChannelsDatabase.Schema, "phoenix.db",
        onConfiguration = { it.copy(extendedConfig = it.extendedConfig.copy(basePath = dir.toString())) }
    )
}