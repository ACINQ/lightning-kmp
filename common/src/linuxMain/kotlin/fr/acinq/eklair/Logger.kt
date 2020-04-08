package fr.acinq.eklair

import android.util.Log

actual fun log(level: LogLevel, tag: String, message: String, error: Throwable) {
    println("[$level] - $tag - $message: ${error.message}")
}

actual fun log(level: LogLevel, tag: String, message: String) {
    println("[$level] - $tag - $message")
}