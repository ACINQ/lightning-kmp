package fr.acinq.lightning.utils

import org.kodein.log.Logger

/**
 * This should be used more largely once https://kotlinlang.org/docs/whatsnew1620.html#prototype-of-context-receivers-for-kotlin-jvm is stable
 */
interface LoggingContext {
    val logger: Logger
}