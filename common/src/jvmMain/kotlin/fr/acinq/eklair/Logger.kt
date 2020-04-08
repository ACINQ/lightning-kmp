package fr.acinq.eklair

import org.slf4j.LoggerFactory

actual fun log(level: LogLevel, tag: String, message: String, error: Throwable) {
    val logger = LoggerFactory.getLogger(tag)
    when (level) {
        is LogLevel.DEBUG -> logger.debug("$message: ", error)
        is LogLevel.INFO -> logger.info("$message: ", error)
        is LogLevel.WARN -> logger.warn("$message: ", error)
        is LogLevel.ERROR -> logger.error("$message: ", error)
    }
}

actual fun log(level: LogLevel, tag: String, message: String) {
    val logger = LoggerFactory.getLogger(tag)
    when (level) {
        is LogLevel.DEBUG -> logger.debug(message)
        is LogLevel.INFO -> logger.info(message)
        is LogLevel.WARN -> logger.warn(message)
        is LogLevel.ERROR -> logger.error(message)
    }
}