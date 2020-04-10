package fr.acinq.eklair

actual class Logger actual constructor(val tag: String) {

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
            log("ERROR", "${message.invoke()}: ${t.message}")
        }
    }

    private fun log(level: String, message: String) {
        println("[$level] $tag: $message")
    }
}