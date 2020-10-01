package fr.acinq.eclair.tests.utils

import org.kodein.log.LoggerFactory
import org.kodein.log.frontend.simplePrintFrontend


actual val testEclairLoggerFactory: LoggerFactory = LoggerFactory(simplePrintFrontend)
