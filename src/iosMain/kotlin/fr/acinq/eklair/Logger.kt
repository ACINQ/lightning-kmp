package fr.acinq.eklair

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter

actual class Logger actual constructor(private val tag: String) {
    private val dateFormatter = NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd HH:mm:ss.SSSSSSZ" // ~ 2020-04-09 07:15:12.255002+0200
    }

    actual fun debug(message: () -> String) {
        log("DEBUG", message.invoke())
    }

    actual fun info(message: () -> String) {
        log("INFO", message.invoke())
    }

    actual fun warn(message: () -> String) {
        log("WARN", message.invoke())
    }

    actual fun error(message: () -> String, t: Throwable?) {
        if (t == null) {
            log("ERROR", message.invoke())
        } else {
            log("ERROR", "$message: ${t.message}")
        }
    }

    private fun log(level: String, message: String) {
        val now = dateFormatter.stringFromDate(NSDate())
        println("$now [$level] $tag: $message")
    }
}
