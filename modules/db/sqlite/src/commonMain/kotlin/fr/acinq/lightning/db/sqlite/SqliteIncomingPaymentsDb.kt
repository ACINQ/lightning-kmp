package fr.acinq.lightning.db.sqlite

import app.cash.sqldelight.db.SqlDriver
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.db.*
import fr.acinq.lightning.db.sqlite.adapters.JsonColumnAdapter
import fr.acinq.lightning.db.sqlite.converters.IncomingLightningPaymentConverter
import fr.acinq.lightning.db.sqlite.converters.IncomingOnChainPaymentConverter

class SqliteIncomingPaymentsDb(driver: SqlDriver) : IncomingPaymentsDb {
    private val inLightningQueries = InLightningQueries(driver, In_lightning.Adapter(JsonColumnAdapter(IncomingLightningPaymentConverter)))
    private val inOnChainQueries = InOnChainQueries(driver, In_onchain.Adapter(JsonColumnAdapter(IncomingOnChainPaymentConverter)))

    override suspend fun addIncomingPayment(incomingPayment: IncomingPayment) {
        when (incomingPayment) {
            is LightningIncomingPayment ->
                inLightningQueries.insert(
                    id = incomingPayment.id.toString(),
                    amount_msat = incomingPayment.amount.msat,
                    fees_msat = incomingPayment.fees.msat,
                    payment_hash = incomingPayment.paymentHash.toByteArray(),
                    payment_preimage = incomingPayment.paymentPreimage.toByteArray(),
                    created_at = incomingPayment.createdAt,
                    received_at = incomingPayment.completedAt,
                    json = incomingPayment
                )
            is OnChainIncomingPayment ->
                inOnChainQueries.insert(
                    id = incomingPayment.id.toString(),
                    channel_id = incomingPayment.channelId.toByteArray(),
                    tx_id = incomingPayment.txId.value.toByteArray(),
                    created_at = incomingPayment.createdAt,
                    confirmed_at = incomingPayment.confirmedAt,
                    locked_at = incomingPayment.lockedAt,
                    json = incomingPayment
                )
            else -> TODO()
        }
    }

    override suspend fun getLightningIncomingPayment(paymentHash: ByteVector32): LightningIncomingPayment? {
        return inLightningQueries.getByPaymentHash(paymentHash.toByteArray())
            .executeAsOneOrNull()?.json
    }

    override suspend fun receiveLightningPayment(paymentHash: ByteVector32, parts: List<LightningIncomingPayment.Received.Part>, receivedAt: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun listLightningExpiredPayments(fromCreatedAt: Long, toCreatedAt: Long): List<LightningIncomingPayment> {
        return inLightningQueries.listCreatedBetween(fromCreatedAt, toCreatedAt)
            .executeAsList()
            .map { it.json }
            .filterIsInstance<Bolt11IncomingPayment>()
            .filter { it.received == null && it.paymentRequest.isExpired() }
    }

    override suspend fun removeLightningIncomingPayment(paymentHash: ByteVector32): Boolean {
        return inLightningQueries.transactionWithResult {
            inLightningQueries.delete(payment_hash = paymentHash.toByteArray())
            inLightningQueries.changes().executeAsOne() != 0L
        }
    }
}