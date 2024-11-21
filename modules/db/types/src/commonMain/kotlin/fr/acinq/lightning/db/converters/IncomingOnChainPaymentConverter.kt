package fr.acinq.lightning.db.converters

import fr.acinq.lightning.db.types.IncomingOnChainPayment

internal object IncomingOnChainPaymentConverter : Converter<fr.acinq.lightning.db.OnChainIncomingPayment, IncomingOnChainPayment> {

    override fun toCoreType(o: IncomingOnChainPayment): fr.acinq.lightning.db.OnChainIncomingPayment = when (o) {
        is IncomingOnChainPayment.NewChannelIncomingPayment.V0 -> fr.acinq.lightning.db.NewChannelIncomingPayment(
            id = o.id,
            amountReceived = o.amountReceived,
            serviceFee = o.serviceFee,
            miningFee = o.miningFee,
            channelId = o.channelId,
            txId = o.txId,
            localInputs = o.localInputs,
            createdAt = o.createdAt,
            confirmedAt = o.confirmedAt,
            lockedAt = o.lockedAt,
        )
        is IncomingOnChainPayment.SpliceInIncomingPayment.V0 -> fr.acinq.lightning.db.SpliceInIncomingPayment(
            id = o.id,
            amountReceived = o.amountReceived,
            miningFee = o.miningFee,
            channelId = o.channelId,
            txId = o.txId,
            localInputs = o.localInputs,
            createdAt = o.createdAt,
            confirmedAt = o.confirmedAt,
            lockedAt = o.lockedAt,
        )
    }

    override fun toDbType(o: fr.acinq.lightning.db.OnChainIncomingPayment): IncomingOnChainPayment = when (o) {
        is fr.acinq.lightning.db.NewChannelIncomingPayment -> IncomingOnChainPayment.NewChannelIncomingPayment.V0(
            id = o.id,
            amountReceived = o.amountReceived,
            serviceFee = o.serviceFee,
            miningFee = o.miningFee,
            channelId = o.channelId,
            txId = o.txId,
            localInputs = o.localInputs,
            createdAt = o.createdAt,
            confirmedAt = o.confirmedAt,
            lockedAt = o.lockedAt,
        )
        is fr.acinq.lightning.db.SpliceInIncomingPayment -> IncomingOnChainPayment.SpliceInIncomingPayment.V0(
            id = o.id,
            amountReceived = o.amountReceived,
            miningFee = o.miningFee,
            channelId = o.channelId,
            txId = o.txId,
            localInputs = o.localInputs,
            createdAt = o.createdAt,
            confirmedAt = o.confirmedAt,
            lockedAt = o.lockedAt,
        )
    }
}