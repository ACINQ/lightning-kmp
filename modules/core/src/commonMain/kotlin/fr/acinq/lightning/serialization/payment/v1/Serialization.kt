package fr.acinq.lightning.serialization.payment.v1

import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.db.*
import fr.acinq.lightning.serialization.OutputExtensions.writeByteVector32
import fr.acinq.lightning.serialization.OutputExtensions.writeCollection
import fr.acinq.lightning.serialization.OutputExtensions.writeDelimited
import fr.acinq.lightning.serialization.OutputExtensions.writeNullable
import fr.acinq.lightning.serialization.OutputExtensions.writeNumber
import fr.acinq.lightning.serialization.OutputExtensions.writeString
import fr.acinq.lightning.serialization.OutputExtensions.writeTxId
import fr.acinq.lightning.serialization.OutputExtensions.writeUuid

@Suppress("DEPRECATION")
object Serialization {

    const val VERSION_MAGIC = 1

    fun serialize(o: WalletPayment): ByteArray {
        val out = ByteArrayOutput()
        out.write(VERSION_MAGIC)
        out.writeWalletPayment(o)
        return out.toByteArray()
    }

    private fun Output.writeWalletPayment(o: WalletPayment) = when (o) {
        is IncomingPayment -> {
            write(0x00); writeIncomingPayment(o)
        }
        else -> TODO()
    }

    private fun Output.writeIncomingPayment(o: IncomingPayment) = when (o) {
        is Bolt11IncomingPayment -> {
            write(0x00); writeBolt11IncomingPayment(o)
        }
        is Bolt12IncomingPayment -> {
            write(0x01); writeBolt12IncomingPayment(o)
        }
        is NewChannelIncomingPayment -> {
            write(0x02); writeNewChannelIncomingPayment(o)
        }
        is SpliceInIncomingPayment -> {
            write(0x03); writeSpliceInIncomingPayment(o)
        }
        is LegacyPayToOpenIncomingPayment -> {
            write(0x04); writeLegacyPayToOpenIncomingPayment(o)
        }
        is LegacySwapInIncomingPayment -> {
            write(0x05); writeLegacySwapInIncomingPayment(o)
        }
    }

    private fun Output.writeBolt11IncomingPayment(o: Bolt11IncomingPayment) = o.run {
        writeByteVector32(paymentPreimage)
        writeString(paymentRequest.write())
        writeCollection(o.parts) { writeLightningIncomingPaymentPart(it) }
        writeNumber(createdAt)
    }

    private fun Output.writeBolt12IncomingPayment(o: Bolt12IncomingPayment) = o.run {
        writeByteVector32(paymentPreimage)
        writeDelimited(metadata.encode().toByteArray())
        writeCollection(o.parts) { writeLightningIncomingPaymentPart(it) }
        writeNumber(createdAt)
    }

    private fun Output.writeLightningIncomingPaymentPart(o: LightningIncomingPayment.Part) = when (o) {
        is LightningIncomingPayment.Part.Htlc -> {
            write(0x00)
            writeNumber(o.amountReceived.toLong())
            writeByteVector32(o.channelId)
            writeNumber(o.htlcId)
            writeNullable(o.fundingFee) {
                writeNumber(it.amount.toLong())
                writeTxId(it.fundingTxId)
            }
            writeNumber(o.receivedAt)
        }
        is LightningIncomingPayment.Part.FeeCredit -> {
            write(0x01)
            writeNumber(o.amountReceived.toLong())
            writeNumber(o.receivedAt)
        }
    }

    private fun Output.writeNewChannelIncomingPayment(o: NewChannelIncomingPayment) = o.run {
        writeUuid(id)
        writeNumber(amountReceived.toLong())
        writeNumber(serviceFee.toLong())
        writeNumber(miningFee.toLong())
        writeByteVector32(channelId)
        writeTxId(txId)
        writeCollection(localInputs) { writeOutPoint(it) }
        writeNumber(createdAt)
        writeNullable(confirmedAt) { writeNumber(it) }
        writeNullable(lockedAt) { writeNumber(it) }
    }

    private fun Output.writeSpliceInIncomingPayment(o: SpliceInIncomingPayment) = o.run {
        writeUuid(id)
        writeNumber(amountReceived.toLong())
        writeNumber(miningFee.toLong())
        writeByteVector32(channelId)
        writeTxId(txId)
        writeCollection(localInputs) { writeOutPoint(it) }
        writeNumber(createdAt)
        writeNullable(confirmedAt) { writeNumber(it) }
        writeNullable(lockedAt) { writeNumber(it) }
    }

    private fun Output.writeLegacyPayToOpenIncomingPayment(o: LegacyPayToOpenIncomingPayment) = o.run {
        writeByteVector32(paymentPreimage)
        when (origin) {
            is LegacyPayToOpenIncomingPayment.Origin.Invoice -> {
                write(0x11); writeString(origin.paymentRequest.write())
            }
            is LegacyPayToOpenIncomingPayment.Origin.Offer -> {
                write(0x12); writeDelimited(origin.metadata.encode().toByteArray())
            }
        }
        writeCollection(parts) {
            when (it) {
                is LegacyPayToOpenIncomingPayment.Part.Lightning -> {
                    write(0x01)
                    writeNumber(it.amountReceived.toLong())
                    writeByteVector32(it.channelId)
                    writeNumber(it.htlcId)
                }
                is LegacyPayToOpenIncomingPayment.Part.OnChain -> {
                    write(0x02)
                    writeNumber(it.amountReceived.toLong())
                    writeNumber(it.serviceFee.toLong())
                    writeNumber(it.miningFee.toLong())
                    writeByteVector32(it.channelId)
                    writeTxId(it.txId)
                    writeNullable(it.confirmedAt) { writeNumber(it) }
                    writeNullable(it.lockedAt) { writeNumber(it) }
                }
            }
        }
        writeNumber(createdAt)
        writeNullable(completedAt) { writeNumber(it) }
    }

    private fun Output.writeLegacySwapInIncomingPayment(o: LegacySwapInIncomingPayment) = o.run {
        writeUuid(id)
        writeNumber(amountReceived.toLong())
        writeNumber(fees.toLong())
        writeNullable(address) { writeString(it) }
        writeNumber(createdAt)
        writeNullable(completedAt) { writeNumber(it) }
    }

    private fun Output.writeOutPoint(o: OutPoint) = o.run {
        writeTxId(txid)
        writeNumber(index)
    }
}