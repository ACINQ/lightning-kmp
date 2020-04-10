package fr.acinq.eklair


abstract class Logging {
    val logger by lazy {
        Logger(this::class.simpleName ?: "noname")
    }
}

expect class Logger(tag: String) {
    fun debug(message: () -> String)
    fun info(message: () -> String)
    fun warn(message: () -> String)
    fun error(message: () -> String, t: Throwable? = null)
}
