package fr.acinq.eclair.utils

import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger
import kotlin.reflect.KClass
import kotlin.reflect.KProperty


@Suppress("ObjectPropertyName")
private var EclairLoggerFactory: LoggerFactory? = null

fun setEclairLoggerFactory(loggerFactory: LoggerFactory) {
    require(EclairLoggerFactory == null) { "EclairLoggerFactory has already been accessed." }
    EclairLoggerFactory = loggerFactory
}

fun eclairLogger(of: KClass<*>) = lazy {
    val factory = EclairLoggerFactory ?: LoggerFactory.default.also { setEclairLoggerFactory(it) }
    factory.newLogger(of)
}

@Suppress("unused")
inline fun <reified T> T.eclairLogger() = eclairLogger(T::class)

inline fun <reified T> eclairLogger() = eclairLogger(T::class)

/*
    Workaround for "MPP: Can't stop on breakpoint anywhere in file if it contains lazy"
    https://youtrack.jetbrains.com/issue/KT-41471
 */
operator fun <T> Lazy<T>.getValue(thisRef: Any?, property: KProperty<*>) = value