package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.Features
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.utils.currentTimestampSeconds

sealed interface PaymentRequest {
    val amount: MilliSatoshi?
    val paymentHash: ByteVector32
    val features: Features

    fun isExpired(currentTimestampSeconds: Long = currentTimestampSeconds()): Boolean
}