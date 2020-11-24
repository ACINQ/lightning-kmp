package fr.acinq.eclair.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.utils.currentTimestampMillis

class InMemoryPaymentsDb : PaymentsDb {
    private val payments = mutableMapOf<ByteVector32, IncomingPayment>()

    override suspend fun addIncomingPayment(pr: PaymentRequest, preimage: ByteVector32, details: IncomingPaymentDetails) {
        require(!payments.contains(pr.paymentHash)) { "an incoming payment for ${pr.paymentHash} already exists" }
        payments[pr.paymentHash] = IncomingPayment(pr, preimage, details, currentTimestampMillis(), IncomingPaymentStatus.Pending)
    }

    override suspend fun getIncomingPayment(paymentHash: ByteVector32): IncomingPayment? {
        val payment = payments[paymentHash]
        return when {
            payment == null -> null
            payment.status == IncomingPaymentStatus.Pending && payment.paymentRequest.isExpired() -> {
                val expired = payment.copy(status = IncomingPaymentStatus.Expired)
                payments[paymentHash] = expired
                expired
            }
            else -> payment
        }
    }

    override suspend fun receivePayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: Long) {
        when (val payment = payments[paymentHash]) {
            null -> Unit // no-op
            else -> payments[paymentHash] = payment.copy(status = IncomingPaymentStatus.Received(amount, receivedAt))
        }
    }

    override suspend fun listReceivedPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter>): List<IncomingPayment> =
        payments.values
            .filter { it.status is IncomingPaymentStatus.Received && it.details.matchesFilters(filters) }
            .sortedByDescending { it.createdAt }
            .drop(skip)
            .take(count)
}