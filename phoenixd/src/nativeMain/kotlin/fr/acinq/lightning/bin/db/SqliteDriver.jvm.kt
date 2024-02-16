package fr.acinq.lightning.bin.db

import com.squareup.sqldelight.db.SqlDriver
import com.squareup.sqldelight.drivers.native.NativeSqliteDriver
import fr.acinq.phoenix.db.ChannelsDatabase


actual val createAppDbDriver: SqlDriver
    get() = NativeSqliteDriver(ChannelsDatabase.Schema, "channels.db")