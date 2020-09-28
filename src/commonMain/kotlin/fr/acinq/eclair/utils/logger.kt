package fr.acinq.eclair.utils

import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger


expect var EclairLoggerFactory: LoggerFactory

inline fun <reified T> T.newEclairLogger() = EclairLoggerFactory.newLogger(T::class)
