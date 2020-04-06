package fr.acinq.eklair

sealed class LogLevel {
  object DEBUG : LogLevel()
  object INFO : LogLevel()
  object WARN : LogLevel()
  object ERROR : LogLevel()
}

expect fun log(level: LogLevel, tag: String, message: String)

expect fun log(level: LogLevel, tag: String, message: String, error: Throwable)
