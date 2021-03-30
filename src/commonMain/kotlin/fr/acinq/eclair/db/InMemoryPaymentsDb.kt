package fr.acinq.eclair.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.channel.ChannelException
import fr.acinq.eclair.payment.FinalFailure
import fr.acinq.eclair.payment.OutgoingPaymentFailure
import fr.acinq.eclair.utils.Either
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.toByteVector32
import fr.acinq.eclair.wire.FailureMessage

class InMemoryPaymentsDb : PaymentsDb {
    private val incoming = mutableMapOf<ByteVector32, IncomingPayment>()
    private val outgoing = mutableMapOf<UUID, OutgoingPayment>()
    private val outgoingParts = mutableMapOf<UUID, Pair<UUID, OutgoingPayment.Part>>()

    override suspend fun addIncomingPayment(preimage: ByteVector32, origin: IncomingPayment.Origin, createdAt: Long) {
        val paymentHash = Crypto.sha256(preimage).toByteVector32()
        require(!incoming.contains(paymentHash)) { "an incoming payment for $paymentHash already exists" }
        incoming[paymentHash] = IncomingPayment(preimage, origin, null, createdAt)
    }

    override suspend fun getIncomingPayment(paymentHash: ByteVector32): IncomingPayment? = incoming[paymentHash]

    override suspend fun receivePayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedWith: IncomingPayment.ReceivedWith, receivedAt: Long) {
        when (val payment = incoming[paymentHash]) {
            null -> Unit // no-op
            else -> incoming[paymentHash] = run {
                val alreadyReceived = payment.received?.amount ?: 0.msat
                payment.copy(received = IncomingPayment.Received(amount + alreadyReceived, receivedWith, receivedAt))
            }
        }
    }

    override suspend fun addAndReceivePayment(preimage: ByteVector32, origin: IncomingPayment.Origin, amount: MilliSatoshi, receivedWith: IncomingPayment.ReceivedWith, createdAt: Long, receivedAt: Long) {
        val paymentHash = preimage.sha256()
        incoming[paymentHash] = IncomingPayment(preimage, origin, IncomingPayment.Received(amount, receivedWith, receivedAt), createdAt)
    }

    override suspend fun listReceivedPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter>): List<IncomingPayment> =
        incoming.values
            .asSequence()
            .filter { it.received != null && it.origin.matchesFilters(filters) }
            .sortedByDescending { WalletPayment.completedAt(it) }
            .drop(skip)
            .take(count)
            .toList()

    override suspend fun addOutgoingPayment(outgoingPayment: OutgoingPayment) {
        require(!outgoing.contains(outgoingPayment.id)) { "an outgoing payment with id=${outgoingPayment.id} already exists" }
        outgoingPayment.parts.forEach { require(!outgoingParts.contains(it.id)) { "an outgoing payment part with id=${it.id} already exists" } }
        outgoing[outgoingPayment.id] = outgoingPayment.copy(parts = listOf())
        outgoingPayment.parts.forEach { outgoingParts[it.id] = Pair(outgoingPayment.id, it) }
    }

    override suspend fun getOutgoingPayment(id: UUID): OutgoingPayment? {
        return outgoing[id]?.let { payment ->
            val parts = outgoingParts.values.filter { it.first == payment.id }.map { it.second }
            return when (payment.status) {
                is OutgoingPayment.Status.Completed.Succeeded -> {
                    payment.copy(parts = parts.filter { it.status is OutgoingPayment.Part.Status.Succeeded })
                }
                else -> payment.copy(parts = parts)
            }
        }
    }

    override suspend fun completeOutgoingPayment(id: UUID, completed: OutgoingPayment.Status.Completed) {
        require(outgoing.contains(id)) { "outgoing payment with id=$id doesn't exist" }
        val payment = outgoing[id]!!
        outgoing[id] = payment.copy(status = completed)
    }

    override suspend fun addOutgoingParts(parentId: UUID, parts: List<OutgoingPayment.Part>) {
        require(outgoing.contains(parentId)) { "parent outgoing payment with id=$parentId doesn't exist" }
        parts.forEach { require(!outgoingParts.contains(it.id)) { "an outgoing payment part with id=${it.id} already exists" } }
        parts.forEach { outgoingParts[it.id] = Pair(parentId, it) }
    }

    override suspend fun updateOutgoingPart(partId: UUID, failure: Either<ChannelException, FailureMessage>, completedAt: Long) {
        require(outgoingParts.contains(partId)) { "outgoing payment part with id=$partId doesn't exist" }
        val (parentId, part) = outgoingParts[partId]!!
        outgoingParts[partId] = Pair(parentId, part.copy(status = OutgoingPaymentFailure.convertFailure(failure, completedAt)))
    }

    override suspend fun updateOutgoingPart(partId: UUID, preimage: ByteVector32, completedAt: Long) {
        require(outgoingParts.contains(partId)) { "outgoing payment part with id=$partId doesn't exist" }
        val (parentId, part) = outgoingParts[partId]!!
        outgoingParts[partId] = Pair(parentId, part.copy(status = OutgoingPayment.Part.Status.Succeeded(preimage, completedAt)))
    }

    override suspend fun getOutgoingPart(partId: UUID): OutgoingPayment? {
        return outgoingParts[partId]?.let { (parentId, _) ->
            require(outgoing.contains(parentId)) { "parent outgoing payment with id=$parentId doesn't exist" }
            getOutgoingPayment(parentId)
        }
    }

    override suspend fun listOutgoingPayments(paymentHash: ByteVector32): List<OutgoingPayment> {
        return outgoing.values.filter { it.paymentHash == paymentHash }.map { payment ->
            val parts = outgoingParts.values.filter { it.first == payment.id }.map { it.second }
            payment.copy(parts = parts)
        }
    }

    override suspend fun listOutgoingPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter>): List<OutgoingPayment> =
        outgoing.values
            .asSequence()
            .filter { it.details.matchesFilters(filters) && (it.status is OutgoingPayment.Status.Completed) }
            .sortedByDescending { WalletPayment.completedAt(it) }
            .drop(skip)
            .take(count)
            .toList()

    override suspend fun listPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter>): List<WalletPayment> {
        val incoming: List<WalletPayment> = listReceivedPayments(count + skip, 0, filters)
        val outgoing: List<WalletPayment> = listOutgoingPayments(count + skip, 0, filters)
        return (incoming + outgoing)
            .sortedByDescending { WalletPayment.completedAt(it) }
            .drop(skip)
            .take(count)
    }
}