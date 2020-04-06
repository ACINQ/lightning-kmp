package fr.acinq.eklair

import platform.Foundation.NSLog

actual fun log(level: LogLevel, tag: String, message: String, error: Throwable) {
  NSLog("[$level]: ($tag), $message, $error")
}

actual fun log(level: LogLevel, tag: String, message: String) {
  NSLog("[$level]: ($tag)")
}