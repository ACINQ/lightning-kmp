package fr.acinq.lightning.tests.utils

import org.kodein.log.LoggerFactory
import org.kodein.log.frontend.simplePrintFrontend


actual val testLightningLoggerFactory: LoggerFactory = LoggerFactory(simplePrintFrontend)
