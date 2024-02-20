package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.utils.Try
import fr.acinq.lightning.Features
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.utils.currentTimestampSeconds

sealed class PaymentRequest {
    abstract val amount: MilliSatoshi?
    abstract val paymentHash: ByteVector32
    abstract val features: Features

    abstract fun isExpired(currentTimestampSeconds: Long = currentTimestampSeconds()): Boolean

    abstract fun write(): String

    companion object {
        fun read(input: String): Try<PaymentRequest> =
            if (input.startsWith(Bolt12Invoice.hrp, ignoreCase = true)) {
                Bolt12Invoice.fromString(input).map { it }
            } else {
                Bolt11Invoice.read(input).map { it }
            }
        }
}