package fr.acinq.lightning.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.channel.ChannelException
import fr.acinq.lightning.payment.FinalFailure
import fr.acinq.lightning.payment.OutgoingPaymentFailure
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.toByteVector32
import fr.acinq.lightning.wire.FailureMessage

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

    override suspend fun receivePayment(paymentHash: ByteVector32, receivedWith: Set<IncomingPayment.ReceivedWith>, receivedAt: Long) {
        when (val payment = incoming[paymentHash]) {
            null -> Unit // no-op
            else -> incoming[paymentHash] = run {
                payment.copy(received = IncomingPayment.Received(
                    receivedWith = (payment.received?.receivedWith ?: emptySet()) + receivedWith,
                    receivedAt = receivedAt))
            }
        }
    }

    override suspend fun addAndReceivePayment(preimage: ByteVector32, origin: IncomingPayment.Origin, receivedWith: Set<IncomingPayment.ReceivedWith>, createdAt: Long, receivedAt: Long) {
        val paymentHash = preimage.sha256()
        incoming[paymentHash] = IncomingPayment(preimage, origin, IncomingPayment.Received(receivedWith, receivedAt), createdAt)
    }

    override suspend fun updateNewChannelReceivedWithChannelId(paymentHash: ByteVector32, channelId: ByteVector32) {
        val payment = incoming[paymentHash]
        when (payment?.received?.receivedWith) {
            null -> Unit // no-op
            else -> incoming[paymentHash] = run {
                val receivedWith = payment.received.receivedWith.map {
                    when (it) {
                        is IncomingPayment.ReceivedWith.NewChannel -> it.copy(channelId = channelId)
                        else -> it
                    }
                }.toSet()
                payment.copy(received = payment.received.copy(receivedWith = receivedWith))
            }
        }
    }

    override suspend fun listReceivedPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter>): List<IncomingPayment> =
        incoming.values
            .asSequence()
            .filter { it.received != null && it.origin.matchesFilters(filters) }
            .sortedByDescending { it.completedAt() }
            .drop(skip)
            .take(count)
            .toList()

    override suspend fun listIncomingPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter>): List<IncomingPayment> =
        incoming.values
            .asSequence()
            .filter { it.origin.matchesFilters(filters) }
            .sortedByDescending { it.createdAt }
            .drop(skip)
            .take(count)
            .toList()
    
    override suspend fun listExpiredPayments(fromCreatedAt: Long, toCreatedAt: Long): List<IncomingPayment> =
        incoming.values
            .asSequence()
            .filter { it.createdAt in fromCreatedAt until toCreatedAt }
            .filter { it.isExpired() }
            .filter { it.received == null }
            .sortedByDescending { it.createdAt }
            .toList()

    override suspend fun removeIncomingPayment(paymentHash: ByteVector32): Boolean {
        val payment = getIncomingPayment(paymentHash)
        return when (payment?.received) {
            null -> incoming.remove(paymentHash) != null
            else -> false // do nothing if payment already partially paid
        }
    }

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
                    payment.copy(parts = parts.filter {
                        (it is OutgoingPayment.LightningPart && it.status is OutgoingPayment.LightningPart.Status.Succeeded) || it is OutgoingPayment.ClosingTxPart
                    })
                }
                else -> payment.copy(parts = parts)
            }
        }
    }

    override suspend fun completeOutgoingPaymentForClosing(id: UUID, parts: List<OutgoingPayment.ClosingTxPart>, completedAt: Long) {
        require(outgoing.contains(id)) { "outgoing payment with id=$id doesn't exist" }
        val payment = outgoing[id]!!
        parts.forEach { require(!outgoingParts.contains(it.id)) { "an outgoing payment part with id=${it.id} already exists" } }
        parts.forEach { outgoingParts[it.id] = Pair(id, it) }


        outgoing[id] = payment.copy(status = OutgoingPayment.Status.Completed.Succeeded.OnChain(completedAt))
    }

    override suspend fun completeOutgoingPaymentOffchain(id: UUID, preimage: ByteVector32, completedAt: Long) {
        require(outgoing.contains(id)) { "outgoing payment with id=$id doesn't exist" }
        val payment = outgoing[id]!!
        outgoing[id] = payment.copy(status = OutgoingPayment.Status.Completed.Succeeded.OffChain(preimage = preimage, completedAt = completedAt))
    }

    override suspend fun completeOutgoingPaymentFailed(id: UUID, finalFailure: FinalFailure, completedAt: Long) {
        require(outgoing.contains(id)) { "outgoing payment with id=$id doesn't exist" }
        val payment = outgoing[id]!!
        outgoing[id] = payment.copy(status = OutgoingPayment.Status.Completed.Failed(reason = finalFailure, completedAt = completedAt))
    }

    override suspend fun addOutgoingLightningParts(parentId: UUID, parts: List<OutgoingPayment.LightningPart>) {
        require(outgoing.contains(parentId)) { "parent outgoing payment with id=$parentId doesn't exist" }
        parts.forEach { require(!outgoingParts.contains(it.id)) { "an outgoing payment part with id=${it.id} already exists" } }
        parts.forEach { outgoingParts[it.id] = Pair(parentId, it) }
    }

    override suspend fun completeOutgoingLightningPart(partId: UUID, failure: Either<ChannelException, FailureMessage>, completedAt: Long) {
        require(outgoingParts.contains(partId)) { "outgoing payment part with id=$partId doesn't exist" }
        val (parentId, part) = outgoingParts[partId]!!
        outgoingParts[partId] = Pair(parentId, (part as OutgoingPayment.LightningPart).copy(status = OutgoingPaymentFailure.convertFailure(failure, completedAt)))
    }

    override suspend fun completeOutgoingLightningPart(partId: UUID, preimage: ByteVector32, completedAt: Long) {
        require(outgoingParts.contains(partId)) { "outgoing payment part with id=$partId doesn't exist" }
        val (parentId, part) = outgoingParts[partId]!!
        outgoingParts[partId] = Pair(parentId, (part as OutgoingPayment.LightningPart).copy(status = OutgoingPayment.LightningPart.Status.Succeeded(preimage, completedAt)))
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
            .sortedByDescending { it.completedAt() }
            .drop(skip)
            .take(count)
            .toList()

    override suspend fun listPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter>): List<WalletPayment> {
        val incoming: List<WalletPayment> = listReceivedPayments(count + skip, 0, filters)
        val outgoing: List<WalletPayment> = listOutgoingPayments(count + skip, 0, filters)
        return (incoming + outgoing)
            .sortedByDescending { it.completedAt() }
            .drop(skip)
            .take(count)
    }
}