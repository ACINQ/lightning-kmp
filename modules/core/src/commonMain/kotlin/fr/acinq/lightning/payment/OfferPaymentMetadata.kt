package fr.acinq.lightning.payment

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.LightningCodecs

/**
 * The flow for Bolt 12 offer payments is the following:
 *
 *  - we create a long-lived, reusable offer
 *  - whenever someone wants to pay that offer, they send us an invoice_request
 *  - we create a Bolt 12 invoice and send it back to the payer
 *  - they send a payment for the corresponding payment_hash
 *
 * When receiving the invoice_request, we have no guarantee that the sender will attempt the payment.
 * If we saved the corresponding invoice in our DB, that could be used as a DoS vector to fill our DB with unpaid invoices.
 * To avoid that, we don't save anything in our DB at that step, and instead include a subset of the invoice fields in the
 * invoice's blinded path. A valid payment for that invoice will include that encrypted data, which contains the metadata
 * we want to store for that payment.
 */
sealed class OfferPaymentMetadata {
    abstract val version: Byte
    abstract val offerId: ByteVector32
    abstract val amount: MilliSatoshi
    abstract val preimage: ByteVector32
    abstract val createdAtMillis: Long
    val paymentHash: ByteVector32 get() = preimage.sha256()

    /** Encode into a format that can be stored in the payments DB. */
    fun encode(): ByteVector {
        val out = ByteArrayOutput()
        LightningCodecs.writeByte(this.version.toInt(), out)
        when (this) {
            is V1 -> this.write(out)
        }
        return out.toByteArray().byteVector()
    }

    /** Encode into a path_id that must be included in the [Bolt12Invoice]'s blinded path. */
    fun toPathId(nodeKey: PrivateKey): ByteVector = when (this) {
        is V1 -> {
            val encoded = this.encode()
            val signature = Crypto.sign(Crypto.sha256(encoded), nodeKey)
            encoded + signature
        }
    }

    /** In this first version, we simply sign the payment metadata to verify its authenticity when receiving the payment. */
    data class V1(
        override val offerId: ByteVector32,
        override val amount: MilliSatoshi,
        override val preimage: ByteVector32,
        val payerKey: PublicKey,
        val payerNote: String?,
        val quantity: Long,
        override val createdAtMillis: Long
    ) : OfferPaymentMetadata() {
        override val version: Byte get() = 1

        fun write(out: Output) {
            LightningCodecs.writeBytes(offerId, out)
            LightningCodecs.writeU64(amount.toLong(), out)
            LightningCodecs.writeBytes(preimage, out)
            LightningCodecs.writeBytes(payerKey.value, out)
            LightningCodecs.writeU64(quantity, out)
            LightningCodecs.writeU64(createdAtMillis, out)
            payerNote?.let { LightningCodecs.writeBytes(it.encodeToByteArray(), out) }
        }

        companion object {
            fun read(input: Input): V1 {
                val offerId = LightningCodecs.bytes(input, 32).byteVector32()
                val amount = LightningCodecs.u64(input).msat
                val preimage = LightningCodecs.bytes(input, 32).byteVector32()
                val payerKey = PublicKey(LightningCodecs.bytes(input, 33))
                val quantity = LightningCodecs.u64(input)
                val createdAtMillis = LightningCodecs.u64(input)
                val payerNote = if (input.availableBytes > 0) LightningCodecs.bytes(input, input.availableBytes).decodeToString() else null
                return V1(offerId, amount, preimage, payerKey, payerNote, quantity, createdAtMillis)
            }
        }
    }

    companion object {
        /**
         * Decode an [OfferPaymentMetadata] encoded using [encode] (e.g. from our payments DB).
         * This function should only be used on data that comes from a trusted source, otherwise it may throw.
         */
        fun decode(encoded: ByteVector): OfferPaymentMetadata {
            val input = ByteArrayInput(encoded.toByteArray())
            return when (val version = LightningCodecs.byte(input)) {
                1 -> V1.read(input)
                else -> throw IllegalArgumentException("unknown offer payment metadata version: $version")
            }
        }

        /**
         * Decode an [OfferPaymentMetadata] stored in a blinded path's path_id field.
         * @return null if the path_id doesn't contain valid data created by us.
         */
        fun fromPathId(nodeId: PublicKey, pathId: ByteVector): OfferPaymentMetadata? {
            if (pathId.isEmpty()) return null
            val input = ByteArrayInput(pathId.toByteArray())
            when (LightningCodecs.byte(input)) {
                1 -> {
                    if (input.availableBytes < 185) return null
                    val metadataSize = input.availableBytes - 64
                    val metadata = LightningCodecs.bytes(input, metadataSize)
                    val signature = LightningCodecs.bytes(input, 64).byteVector64()
                    // Note that the signature includes the version byte.
                    if (!Crypto.verifySignature(Crypto.sha256(pathId.take(1 + metadataSize)), signature, nodeId)) return null
                    // This call is safe since we verified that we have the right number of bytes and the signature was valid.
                    return V1.read(ByteArrayInput(metadata))
                }
                else -> return null
            }
        }
    }
}
