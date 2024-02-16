package fr.acinq.lightning.bin

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.channel.ChannelException
import fr.acinq.lightning.channel.states.PersistedChannelState
import fr.acinq.lightning.db.*
import fr.acinq.lightning.payment.FinalFailure
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.wire.FailureMessage

class SqliteDatabases(override val channels: SqliteChannelsDb, override val payments: PaymentsDb) : Databases

class SqliteChannelsDb : ChannelsDb {
    override suspend fun addOrUpdateChannel(state: PersistedChannelState) {}

    override suspend fun removeChannel(channelId: ByteVector32) {}

    override suspend fun listLocalChannels(): List<PersistedChannelState> = emptyList()

    override suspend fun addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry) {
        TODO("Not yet implemented")
    }

    override suspend fun listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): List<Pair<ByteVector32, CltvExpiry>> {
        TODO("Not yet implemented")
    }

    override fun close() {}

}

class SqlitePaymentsDb : PaymentsDb {
    override suspend fun setLocked(txId: TxId) {
        TODO("Not yet implemented")
    }

    override suspend fun addIncomingPayment(preimage: ByteVector32, origin: IncomingPayment.Origin, createdAt: Long): IncomingPayment {
        TODO("Not yet implemented")
    }

    override suspend fun getIncomingPayment(paymentHash: ByteVector32): IncomingPayment? {
        TODO("Not yet implemented")
    }

    override suspend fun receivePayment(paymentHash: ByteVector32, receivedWith: List<IncomingPayment.ReceivedWith>, receivedAt: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun listExpiredPayments(fromCreatedAt: Long, toCreatedAt: Long): List<IncomingPayment> {
        TODO("Not yet implemented")
    }

    override suspend fun removeIncomingPayment(paymentHash: ByteVector32): Boolean {
        TODO("Not yet implemented")
    }

    override suspend fun addOutgoingPayment(outgoingPayment: OutgoingPayment) {
        TODO("Not yet implemented")
    }

    override suspend fun getLightningOutgoingPayment(id: UUID): LightningOutgoingPayment? {
        TODO("Not yet implemented")
    }

    override suspend fun completeOutgoingPaymentOffchain(id: UUID, preimage: ByteVector32, completedAt: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun completeOutgoingPaymentOffchain(id: UUID, finalFailure: FinalFailure, completedAt: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun addOutgoingLightningParts(parentId: UUID, parts: List<LightningOutgoingPayment.Part>) {
        TODO("Not yet implemented")
    }

    override suspend fun completeOutgoingLightningPart(partId: UUID, failure: Either<ChannelException, FailureMessage>, completedAt: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun completeOutgoingLightningPart(partId: UUID, preimage: ByteVector32, completedAt: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun getLightningOutgoingPaymentFromPartId(partId: UUID): LightningOutgoingPayment? {
        TODO("Not yet implemented")
    }

    override suspend fun listLightningOutgoingPayments(paymentHash: ByteVector32): List<LightningOutgoingPayment> {
        TODO("Not yet implemented")
    }

}