package fr.acinq.lightning.logging

import co.touchlab.kermit.Logger
import fr.acinq.lightning.channel.states.*
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.io.SendPayment
import fr.acinq.lightning.payment.PaymentPart
import fr.acinq.lightning.wire.HasChannelId
import fr.acinq.lightning.wire.HasTemporaryChannelId
import fr.acinq.lightning.wire.LightningMessage

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

    inline fun debug(mdc: Map<String, Any> = emptyMap(), message: () -> String) {
        logger.debug { message() + mdcToString(staticMdc + mdc) }
    }

    inline fun info(mdc: Map<String, Any> = emptyMap(), message: () -> String) {
        logger.info { message() + mdcToString(staticMdc + mdc) }
    }

    inline fun warning(ex: Throwable? = null, mdc: Map<String, Any> = emptyMap(), message: () -> String) {
        logger.warning(ex) { message() + mdcToString(staticMdc + mdc) }
    }

    inline fun error(ex: Throwable? = null, mdc: Map<String, Any> = emptyMap(), message: () -> String) {
        logger.error(ex) { message() + mdcToString(staticMdc + mdc) }
    }

    fun mdcToString(mdc: Map<String, Any>): String {
        return if (mdc.isEmpty()) {
            ""
        } else {
            "\n" + mdc.map { (key, value) -> "    $key: $value" }.joinToString("\n")
        }
    }
}

suspend fun <T> MDCLogger.withMDC(mdc: Map<String, Any>, f: suspend (MDCLogger) -> T): T {
    val logger = this.copy(staticMdc = this.staticMdc + mdc)
    return f(logger)
}



/**
 * Utility functions to build MDC for various objects without polluting main classes
 */

fun PaymentPart.mdc(): Map<String, Any> = mapOf(
    "paymentHash" to paymentHash,
    "amount" to amount,
    "totalAmount" to totalAmount
)

fun SendPayment.mdc(): Map<String, Any> = mapOf(
    "paymentId" to paymentId,
    "paymentHash" to paymentHash,
    "amount" to amount,
    "recipient" to recipient
)

fun LightningOutgoingPayment.mdc(): Map<String, Any> = mapOf(
    "paymentId" to id,
    "paymentHash" to paymentHash,
    "amount" to amount,
    "recipient" to recipient
)

fun ChannelState.mdc(): Map<String, Any> {
    val state = this
    return buildMap {
        put("state", state.stateName)
        when (state) {
            is WaitForOpenChannel -> put("temporaryChannelId", state.temporaryChannelId)
            is WaitForAcceptChannel -> put("temporaryChannelId", state.temporaryChannelId)
            is WaitForFundingCreated -> put("channelId", state.channelId)
            is WaitForFundingSigned -> put("channelId", state.channelId)
            is ChannelStateWithCommitments -> put("channelId", state.channelId)
            is Offline -> put("channelId", state.state.channelId)
            is Syncing -> put("channelId", state.state.channelId)
            else -> {}
        }
        when(state) {
            is ChannelStateWithCommitments -> put("commitments", "active=${state.commitments.active.map { it.fundingTxIndex }} inactive=${state.commitments.inactive.map { it.fundingTxIndex }}")
            else -> {}
        }
    }
}

fun LightningMessage.mdc(): Map<String, Any> {
    val msg = this
    return buildMap {
        when(msg) {
            is HasTemporaryChannelId -> put("temporaryChannelId", msg.temporaryChannelId)
            is HasChannelId -> put("channelId", msg.channelId)
            else -> {}
        }
    }
}