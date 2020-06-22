package fr.acinq.eklair

import org.slf4j.LoggerFactory

actual class Logger actual constructor(tag: String) {
    private val logger: org.slf4j.Logger = LoggerFactory.getLogger(tag)

    actual fun debug(message: () -> String) {
        if (logger.isDebugEnabled) logger.debug(message.invoke())
    }

    actual fun info(message: () -> String) {
        if (logger.isInfoEnabled) logger.info(message.invoke())
    }

    actual fun warn(message: () -> String) {
        if (logger.isWarnEnabled) logger.warn(message.invoke())
    }

    actual fun error(message: () -> String, t: Throwable?) {
        if (t == null) {
            logger.error(message.invoke())
        } else {
            logger.error(message.invoke(), t)
        }
    }
}
