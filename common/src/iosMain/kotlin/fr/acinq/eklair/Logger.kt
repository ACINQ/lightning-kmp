package fr.acinq.eklair

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter

private fun getCurrentTime() = dateFormatter.stringFromDate(NSDate())

private val dateFormatter = NSDateFormatter().apply {
    dateFormat = "yyyy-MM-dd HH:mm:ss.SSSSSSZ" // ~ 2020-04-09 07:15:12.255002+0200
}

private fun log(message: String) {
    println("${getCurrentTime()} ${message}")
}

actual fun log(level: LogLevel, tag: String, message: String, error: Throwable) {
//    val logger = LoggerFactory.getLogger(tag)
//    when (level) {
//        is LogLevel.DEBUG -> logger.debug("$message: ", error)
//        is LogLevel.INFO -> logger.info("$message: ", error)
//        is LogLevel.WARN -> logger.warn("$message: ", error)
//        is LogLevel.ERROR -> logger.error("$message: ", error)
//    }

    log(message)
}

actual fun log(level: LogLevel, tag: String, message: String) {
//    val logger = LoggerFactory.getLogger(tag)
//    when (level) {
//        is LogLevel.DEBUG -> logger.debug(message)
//        is LogLevel.INFO -> logger.info(message)
//        is LogLevel.WARN -> logger.warn(message)
//        is LogLevel.ERROR -> logger.error(message)
//    }

    log(message)
}