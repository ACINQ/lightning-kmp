package fr.acinq.eklair

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter

private fun getCurrentTime() = dateFormatter.stringFromDate(NSDate())

private val dateFormatter = NSDateFormatter().apply {
    dateFormat = "yyyy-MM-dd HH:mm:ss.SSSSSSZ" // ~ 2020-04-09 07:15:12.255002+0200
}

private fun log(level: String, message: String) {
    println("${getCurrentTime()} [${level}]: ${message}")
}

actual fun log(level: LogLevel, tag: String, message: String, error: Throwable) {
    log(level, tag, message)
}

actual fun log(level: LogLevel, tag: String, message: String) {
    when (level) {
        is LogLevel.DEBUG -> log("DEBUG", message)
        is LogLevel.INFO -> log("INFO", message)
        is LogLevel.WARN -> log("WARN", message)
        is LogLevel.ERROR -> log("ERROR", message)
    }
}