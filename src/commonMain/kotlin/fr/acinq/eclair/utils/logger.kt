package fr.acinq.eclair.utils

import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger
import kotlin.properties.PropertyDelegateProvider
import kotlin.reflect.KClass
import kotlin.reflect.KProperty


@Suppress("ObjectPropertyName")
private var EclairLoggerFactory: LoggerFactory? = null

fun setEclairLoggerFactory(loggerFactory: LoggerFactory) {
    require(EclairLoggerFactory == null) { "EclairLoggerFactory has already been accessed." }
    EclairLoggerFactory = loggerFactory
}

class EclairLoggerDelegateProvider(val of: KClass<*>) : PropertyDelegateProvider<Any, Lazy<Logger>> {
    override fun provideDelegate(thisRef: Any, property: KProperty<*>): Lazy<Logger> {
        thisRef.ensureNeverFrozen()
        return lazy {
            val factory = EclairLoggerFactory ?: LoggerFactory.default.also { setEclairLoggerFactory(it) }
            factory.newLogger(of)
        }
    }
}

fun eclairLogger(of: KClass<*>) = EclairLoggerDelegateProvider(of)

@Suppress("unused")
inline fun <reified T> T.eclairLogger() = eclairLogger(T::class)

inline fun <reified T> eclairLogger() = eclairLogger(T::class)
