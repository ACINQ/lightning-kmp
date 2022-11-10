package fr.acinq.lightning.utils

import org.kodein.log.Logger
import org.kodein.log.Logger.Level.*

/**
 * This should be used more largely once https://kotlinlang.org/docs/whatsnew1620.html#prototype-of-context-receivers-for-kotlin-jvm is stable
 */
interface LoggingContext {
    val logger: MDCLogger
}

/**
 * A simpler wrapper on top of [Logger] with better MDC support.
 */
data class MDCLogger(val logger: Logger, val staticMdc: Map<String, Any> = emptyMap()) {
    inline fun debug(mdc: Map<String, Any> = emptyMap(), msgCreator: () -> String) {
        logger.log(level = DEBUG, msgCreator = msgCreator, meta = staticMdc + mdc)
    }

    inline fun info(mdc: Map<String, Any> = emptyMap(), msgCreator: () -> String) {
        logger.log(level = INFO, msgCreator = msgCreator, meta = staticMdc + mdc)
    }

    inline fun warning(ex: Throwable? = null, mdc: Map<String, Any> = emptyMap(), msgCreator: () -> String) {
        logger.log(level = WARNING, error = ex, msgCreator = msgCreator, meta = staticMdc + mdc)
    }

    inline fun error(ex: Throwable? = null, mdc: Map<String, Any> = emptyMap(), msgCreator: () -> String) {
        logger.log(level = ERROR, error = ex, msgCreator = msgCreator, meta = staticMdc + mdc)
    }
}