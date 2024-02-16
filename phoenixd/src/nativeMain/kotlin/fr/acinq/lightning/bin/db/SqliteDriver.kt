package fr.acinq.lightning.bin.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import fr.acinq.phoenix.db.ChannelsDatabase


actual val createAppDbDriver: SqlDriver
    get() = NativeSqliteDriver(ChannelsDatabase.Schema, "channels.db")