package fr.acinq.lightning.serialization.payment.v1

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.byteVector
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.Input
import fr.acinq.lightning.db.*
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.OfferPaymentMetadata
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.wire.LightningCodecs
import fr.acinq.lightning.wire.LightningMessage
import fr.acinq.lightning.wire.LiquidityAds

@Suppress("DEPRECATION")
object Deserialization {

    fun deserialize(bin: ByteArray): WalletPayment {
        val input = ByteArrayInput(bin)
        val version = input.read()
        require(version == Serialization.VERSION_MAGIC) { "incorrect version $version, expected ${Serialization.VERSION_MAGIC}" }
        return input.readWalletPayment()
    }

    private fun Input.readWalletPayment(): WalletPayment = when (val discriminator = read()) {
        0x00 -> readIncomingPayment()
        else -> error("unknown discriminator $discriminator for class ${WalletPayment::class}")
    }

    private fun Input.readIncomingPayment(): IncomingPayment = when (val discriminator = read()) {
        0x00 -> readBolt11IncomingPayment()
        0x01 -> readBolt12IncomingPayment()
        0x02 -> readNewChannelIncomingPayment()
        0x03 -> readSpliceInIncomingPayment()
        0x04 -> readLegacyPayToOpenIncomingPayment()
        0x05 -> readLegacySwapInIncomingPayment()
        else -> error("unknown discriminator $discriminator for class ${IncomingPayment::class}")
    }

    private fun Input.readBolt11IncomingPayment() = Bolt11IncomingPayment(
        preimage = readByteVector32(),
        paymentRequest = Bolt11Invoice.read(readString()).get(),
        parts = readCollection { readLightningIncomingPaymentPart() }.toList(),
        createdAt = readNumber()
    )

    private fun Input.readBolt12IncomingPayment() = Bolt12IncomingPayment(
        preimage = readByteVector32(),
        metadata = OfferPaymentMetadata.decode(readDelimitedByteArray().byteVector()),
        parts = readCollection { readLightningIncomingPaymentPart() }.toList(),
        createdAt = readNumber()
    )

    private fun Input.readLightningIncomingPaymentPart(): LightningIncomingPayment.Part = when (val discriminator = read()) {
        0x00 -> LightningIncomingPayment.Part.Htlc(
            amountReceived = readNumber().msat,
            channelId = readByteVector32(),
            htlcId = readNumber(),
            fundingFee = readNullable {
                LiquidityAds.FundingFee(
                    amount = readNumber().msat,
                    fundingTxId = readTxId()
                )
            },
            receivedAt = readNumber()
        )
        0x01 -> LightningIncomingPayment.Part.FeeCredit(
            amountReceived = readNumber().msat,
            receivedAt = readNumber()
        )
        else -> error("unknown discriminator $discriminator for class ${LightningIncomingPayment.Part::class}")
    }

    private fun Input.readNewChannelIncomingPayment(): NewChannelIncomingPayment = NewChannelIncomingPayment(
        id = readUuid(),
        amountReceived = readNumber().msat,
        serviceFee = readNumber().msat,
        miningFee = readNumber().sat,
        channelId = readByteVector32(),
        txId = readTxId(),
        localInputs = readCollection { readOutPoint() }.toSet(),
        createdAt = readNumber(),
        confirmedAt = readNullable { readNumber() },
        lockedAt = readNullable { readNumber() },
    )

    private fun Input.readSpliceInIncomingPayment(): SpliceInIncomingPayment = SpliceInIncomingPayment(
        id = readUuid(),
        amountReceived = readNumber().msat,
        miningFee = readNumber().sat,
        channelId = readByteVector32(),
        txId = readTxId(),
        localInputs = readCollection { readOutPoint() }.toSet(),
        createdAt = readNumber(),
        confirmedAt = readNullable { readNumber() },
        lockedAt = readNullable { readNumber() },
    )

    private fun Input.readLegacyPayToOpenIncomingPayment(): LegacyPayToOpenIncomingPayment = LegacyPayToOpenIncomingPayment(
        paymentPreimage = readByteVector32(),
        origin = when (val discriminator = read()) {
            0x11 -> LegacyPayToOpenIncomingPayment.Origin.Invoice(Bolt11Invoice.read(readString()).get())
            0x12 -> LegacyPayToOpenIncomingPayment.Origin.Offer(OfferPaymentMetadata.decode(readDelimitedByteArray().byteVector()))
            else -> error("unknown discriminator $discriminator for class ${LegacyPayToOpenIncomingPayment::class}")
        },
        parts = readCollection {
            when (val discriminator = read()) {
                0x01 -> LegacyPayToOpenIncomingPayment.Part.Lightning(
                    amountReceived = readNumber().msat,
                    channelId = readByteVector32(),
                    htlcId = readNumber()
                )
                0x02 -> LegacyPayToOpenIncomingPayment.Part.OnChain(
                    amountReceived = readNumber().msat,
                    serviceFee = readNumber().msat,
                    miningFee = readNumber().sat,
                    channelId = readByteVector32(),
                    txId = readTxId(),
                    confirmedAt = readNullable { readNumber() },
                    lockedAt = readNullable { readNumber() },
                )
                else -> error("unknown discriminator $discriminator for class ${LegacyPayToOpenIncomingPayment::class}")
            }
        }.toList(),
        createdAt = readNumber(),
        completedAt = readNullable { readNumber() }
    )

    private fun Input.readLegacySwapInIncomingPayment(): LegacySwapInIncomingPayment = LegacySwapInIncomingPayment(
        id = readUuid(),
        amountReceived = readNumber().msat,
        fees = readNumber().msat,
        address = readNullable { readString() },
        createdAt = readNumber(),
        completedAt = readNullable { readNumber() }
    )

    private fun Input.readUuid(): UUID = UUID.fromBytes(ByteArray(16).also { read(it, 0, it.size) })

    private fun Input.readOutPoint(): OutPoint = OutPoint(
        txid = readTxId(),
        index = readNumber()
    )

    private fun Input.readNumber(): Long = LightningCodecs.bigSize(this)

    private fun Input.readString(): String = readDelimitedByteArray().decodeToString()

    private fun Input.readByteVector32(): ByteVector32 = ByteVector32(ByteArray(32).also { read(it, 0, it.size) })

    private fun Input.readTxId(): TxId = TxId(readByteVector32())

    private fun Input.readDelimitedByteArray(): ByteArray {
        val size = readNumber().toInt()
        return ByteArray(size).also { read(it, 0, size) }
    }

    private fun Input.readLightningMessage() = LightningMessage.decode(readDelimitedByteArray())

    private fun <T> Input.readCollection(readElem: () -> T): Collection<T> {
        val size = readNumber()
        return buildList {
            repeat(size.toInt()) {
                add(readElem())
            }
        }
    }

    private fun <T : Any> Input.readNullable(readNotNull: () -> T): T? = when (read()) {
        1 -> readNotNull()
        else -> null
    }
}