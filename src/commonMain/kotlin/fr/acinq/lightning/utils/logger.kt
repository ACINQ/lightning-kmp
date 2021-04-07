package fr.acinq.lightning.utils

import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger
import kotlin.properties.PropertyDelegateProvider
import kotlin.reflect.KClass
import kotlin.reflect.KProperty


@Suppress("ObjectPropertyName")
private var LightningLoggerFactory: LoggerFactory? = null

fun setLightningLoggerFactory(loggerFactory: LoggerFactory) {
    require(LightningLoggerFactory == null) { "LightningLoggerFactory has already been accessed." }
    LightningLoggerFactory = loggerFactory
}

class LightningLoggerDelegateProvider(val of: KClass<*>) : PropertyDelegateProvider<Any, Lazy<Logger>> {
    override fun provideDelegate(thisRef: Any, property: KProperty<*>): Lazy<Logger> {
        thisRef.ensureNeverFrozen()
        return lazy {
            val factory = LightningLoggerFactory ?: LoggerFactory.default.also { setLightningLoggerFactory(it) }
            factory.newLogger(of)
        }
    }
}

fun lightningLogger(of: KClass<*>) = LightningLoggerDelegateProvider(of)

@Suppress("unused")
inline fun <reified T> T.lightningLogger() = lightningLogger(T::class)

inline fun <reified T> lightningLogger() = lightningLogger(T::class)
