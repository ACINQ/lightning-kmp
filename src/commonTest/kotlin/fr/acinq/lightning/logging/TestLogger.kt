package fr.acinq.lightning.logging

import co.touchlab.kermit.Logger
import co.touchlab.kermit.NoTagFormatter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import co.touchlab.kermit.platformLogWriter

val Logger.Companion.testLogger: Logger by lazy {
    Logger(
        config = loggerConfigInit(
            platformLogWriter(NoTagFormatter),
            minSeverity = Severity.Info
        ),
        tag = "LightningKmp"
    )
}
