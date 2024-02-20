package fr.acinq.lightning.bin

import app.cash.sqldelight.db.SqlDriver
import kotlinx.io.files.Path

expect val homeDirectory: Path

expect fun createAppDbDriver(dir: Path): SqlDriver