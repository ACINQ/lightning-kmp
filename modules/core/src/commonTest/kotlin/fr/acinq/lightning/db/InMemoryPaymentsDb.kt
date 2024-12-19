package fr.acinq.lightning.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.payment.FinalFailure
import fr.acinq.lightning.utils.UUID

class InMemoryPaymentsDb : PaymentsDb {
    private val incoming = mutableMapOf<ByteVector32, LightningIncomingPayment>()
    private val onchainIncoming = mutableMapOf<UUID, OnChainIncomingPayment>()
    private val outgoing = mutableMapOf<UUID, LightningOutgoingPayment>()
    private val onChainOutgoing = mutableMapOf<TxId, OnChainOutgoingPayment>()
    private val outgoingParts = mutableMapOf<UUID, Pair<UUID, LightningOutgoingPayment.Part>>()
    override suspend fun setLocked(txId: TxId) {}

    override suspend fun addIncomingPayment(incomingPayment: IncomingPayment) {
        when (incomingPayment) {
            is LightningIncomingPayment -> {
                require(!incoming.contains(incomingPayment.paymentHash)) { "an incoming payment for ${incomingPayment.paymentHash} already exists" }
                incoming[incomingPayment.paymentHash] = incomingPayment
            }
            is OnChainIncomingPayment -> {
                require(!onchainIncoming.contains(incomingPayment.id)) { "an incoming payment with id=${incomingPayment.id} already exists" }
                onchainIncoming[incomingPayment.id] = incomingPayment
            }
            else -> TODO()
        }
    }

    override suspend fun getLightningIncomingPayment(paymentHash: ByteVector32): LightningIncomingPayment? = incoming[paymentHash]

    override suspend fun receiveLightningPayment(paymentHash: ByteVector32, parts: List<LightningIncomingPayment.Part>) {
        when (val payment = incoming[paymentHash]) {
            null -> Unit // no-op
            else -> incoming[paymentHash] = payment.addReceivedParts(parts)
        }
    }

    fun listIncomingPayments(count: Int, skip: Int): List<IncomingPayment> =
        incoming.values
            .asSequence()
            .sortedByDescending { it.createdAt }
            .drop(skip)
            .take(count)
            .toList()

    override suspend fun listLightningExpiredPayments(fromCreatedAt: Long, toCreatedAt: Long): List<LightningIncomingPayment> =
        incoming.values
            .asSequence()
            .filter { it.createdAt in fromCreatedAt until toCreatedAt }
            .filter { it.isExpired() }
            .filter { it.parts.isEmpty() }
            .sortedByDescending { it.createdAt }
            .toList()

    override suspend fun removeLightningIncomingPayment(paymentHash: ByteVector32): Boolean {
        val payment = getLightningIncomingPayment(paymentHash)
        return when (payment?.parts?.isEmpty()) {
            true -> incoming.remove(paymentHash) != null
            else -> false // do nothing if payment already partially paid
        }
    }

    override suspend fun addOutgoingPayment(outgoingPayment: OutgoingPayment) {
        require(!outgoing.contains(outgoingPayment.id)) { "an outgoing payment with id=${outgoingPayment.id} already exists" }
        when (outgoingPayment) {
            is LightningOutgoingPayment -> {
                outgoingPayment.parts.forEach { require(!outgoingParts.contains(it.id)) { "an outgoing payment part with id=${it.id} already exists" } }
                outgoing[outgoingPayment.id] = outgoingPayment.copy(parts = listOf())
                outgoingPayment.parts.forEach { outgoingParts[it.id] = Pair(outgoingPayment.id, it) }
            }
            is OnChainOutgoingPayment -> onChainOutgoing[outgoingPayment.txId] = outgoingPayment
        }
    }

    override suspend fun getLightningOutgoingPayment(id: UUID): LightningOutgoingPayment? {
        return outgoing[id]?.let { payment ->
            val parts = outgoingParts.values.filter { it.first == payment.id }.map { it.second }
            return when (payment.status) {
                is LightningOutgoingPayment.Status.Completed.Succeeded -> payment.copy(parts = parts.filter { it.status is LightningOutgoingPayment.Part.Status.Succeeded })
                else -> payment.copy(parts = parts)
            }
        }
    }

    override suspend fun getInboundLiquidityPurchase(fundingTxId: TxId): InboundLiquidityOutgoingPayment? {
        return when (val onChainPayment = onChainOutgoing[fundingTxId]) {
            is InboundLiquidityOutgoingPayment -> onChainPayment
            else -> null
        }
    }

    override suspend fun completeOutgoingPaymentOffchain(id: UUID, preimage: ByteVector32, completedAt: Long) {
        require(outgoing.contains(id)) { "outgoing payment with id=$id doesn't exist" }
        val payment = outgoing[id]!!
        outgoing[id] = payment.copy(status = LightningOutgoingPayment.Status.Completed.Succeeded.OffChain(preimage = preimage, completedAt = completedAt))
    }

    override suspend fun completeOutgoingPaymentOffchain(id: UUID, finalFailure: FinalFailure, completedAt: Long) {
        require(outgoing.contains(id)) { "outgoing payment with id=$id doesn't exist" }
        val payment = outgoing[id]!!
        outgoing[id] = payment.copy(status = LightningOutgoingPayment.Status.Completed.Failed(reason = finalFailure, completedAt = completedAt))
    }

    override suspend fun addOutgoingLightningParts(parentId: UUID, parts: List<LightningOutgoingPayment.Part>) {
        require(outgoing.contains(parentId)) { "parent outgoing payment with id=$parentId doesn't exist" }
        parts.forEach { require(!outgoingParts.contains(it.id)) { "an outgoing payment part with id=${it.id} already exists" } }
        parts.forEach { outgoingParts[it.id] = Pair(parentId, it) }
    }

    override suspend fun completeOutgoingLightningPart(partId: UUID, failure: LightningOutgoingPayment.Part.Status.Failure, completedAt: Long) {
        require(outgoingParts.contains(partId)) { "outgoing payment part with id=$partId doesn't exist" }
        val (parentId, part) = outgoingParts[partId]!!
        outgoingParts[partId] = Pair(parentId, part.copy(status = LightningOutgoingPayment.Part.Status.Failed(failure, completedAt)))
    }

    override suspend fun completeOutgoingLightningPart(partId: UUID, preimage: ByteVector32, completedAt: Long) {
        require(outgoingParts.contains(partId)) { "outgoing payment part with id=$partId doesn't exist" }
        val (parentId, part) = outgoingParts[partId]!!
        outgoingParts[partId] = Pair(parentId, part.copy(status = LightningOutgoingPayment.Part.Status.Succeeded(preimage, completedAt)))
    }

    override suspend fun getLightningOutgoingPaymentFromPartId(partId: UUID): LightningOutgoingPayment? {
        return outgoingParts[partId]?.let { (parentId, _) ->
            require(outgoing.contains(parentId)) { "parent outgoing payment with id=$parentId doesn't exist" }
            getLightningOutgoingPayment(parentId)
        }
    }

    override suspend fun listLightningOutgoingPayments(paymentHash: ByteVector32): List<LightningOutgoingPayment> {
        return outgoing.values.filter { it.paymentHash == paymentHash }.map { payment ->
            val parts = outgoingParts.values.filter { it.first == payment.id }.map { it.second }
            payment.copy(parts = parts)
        }
    }
}