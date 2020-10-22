package fr.acinq.eclair.utils

import org.kodein.log.LoggerFactory
import kotlin.native.concurrent.AtomicReference
import kotlin.native.concurrent.SharedImmutable
import kotlin.native.concurrent.freeze


@SharedImmutable
private val EclairLoggerFactoryReference = AtomicReference(LoggerFactory.default)

actual var EclairLoggerFactory: LoggerFactory
    get() = EclairLoggerFactoryReference.value
    set(factory) {
        EclairLoggerFactoryReference.value = factory.freeze()
    }
