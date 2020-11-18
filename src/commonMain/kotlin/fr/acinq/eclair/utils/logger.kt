package fr.acinq.eclair.utils

import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger
import kotlin.jvm.JvmName
import kotlin.reflect.KClass


@Suppress("ObjectPropertyName")
private var EclairLoggerFactory: LoggerFactory? = null

fun setEclairLoggerFactory(loggerFactory: LoggerFactory) {
    require(EclairLoggerFactory == null) { "EclairLoggerFactory has already been accessed." }
    EclairLoggerFactory = loggerFactory
}

fun newEclairLogger(of: KClass<*>) = lazy {
    val factory = EclairLoggerFactory ?: LoggerFactory.default.also { setEclairLoggerFactory(it) }
    factory.newLogger(of)
}

@Suppress("unused")
inline fun <reified T> T.newEclairLogger() = newEclairLogger(T::class)

inline fun <reified T> newEclairLogger() = newEclairLogger(T::class)
