package fr.acinq.lightning.payment

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.LightningCodecs
import kotlin.experimental.and
import kotlin.experimental.or
import kotlin.math.min

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
    abstract val createdAtSeconds: Long
    abstract val relativeExpirySeconds: Long?
    val paymentHash: ByteVector32 get() = preimage.sha256()

    /** Encode into a format that can be stored in the payments DB. */
    fun encode(): ByteVector {
        val out = ByteArrayOutput()
        LightningCodecs.writeByte(this.version.toInt(), out)
        when (this) {
            is V1 -> this.write(out)
            is V2 -> this.write(out)
        }
        return out.toByteArray().byteVector()
    }

    /** Encode into a path_id that must be included in the [Bolt12Invoice]'s blinded path. */
    fun toPathId(nodeKey: PrivateKey): ByteVector {
        val encoded = this.encode()
        val signature = Crypto.sign(Crypto.sha256(encoded), nodeKey)
        return encoded + signature
    }

    /** In this first version, we simply sign the payment metadata to verify its authenticity when receiving the payment. */
    data class V1(
        override val offerId: ByteVector32,
        override val amount: MilliSatoshi,
        override val preimage: ByteVector32,
        val payerKey: PublicKey,
        val payerNote: String?,
        val quantity: Long,
        val createdAtMillis: Long
    ) : OfferPaymentMetadata() {
        override val version: Byte get() = 1

        override val createdAtSeconds: Long get() = createdAtMillis / 1000
        override val relativeExpirySeconds: Long? get() = null

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
            val minLength: Int get() = 121

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

    data class V2(
        override val offerId: ByteVector32,
        override val amount: MilliSatoshi,
        override val preimage: ByteVector32,
        override val createdAtSeconds: Long,
        override val relativeExpirySeconds: Long?,
        val description: String?,
        val payerKey: PublicKey?,
        val payerNote: String?,
        val quantity: Long?,

    ) : OfferPaymentMetadata() {
        override val version: Byte get() = 2

        fun write(out: Output) {
            LightningCodecs.writeBytes(offerId, out)
            LightningCodecs.writeBigSize(amount.toLong(), out)
            LightningCodecs.writeBytes(preimage, out)
            LightningCodecs.writeBigSize(createdAtSeconds, out)

            var flags: Byte = 0
            if (relativeExpirySeconds != null) { flags = flags or 0b00001 }
            if (payerKey != null)              { flags = flags or 0b00010 }
            if (quantity != null)              { flags = flags or 0b00100 }
            if (description != null)           { flags = flags or 0b01000 }
            if (payerNote != null)             { flags = flags or 0b10000 }
            LightningCodecs.writeByte(flags.toInt(), out)

            relativeExpirySeconds?.let { LightningCodecs.writeBigSize(it, out) }
            payerKey?.let { LightningCodecs.writeBytes(it.value, out) }
            quantity?.let { LightningCodecs.writeU64(it, out) }
            description?.let {
                if (payerNote != null) { LightningCodecs.writeBigSize(it.length.toLong(), out) }
                LightningCodecs.writeBytes(it.encodeToByteArray(), out)
            }
            payerNote?.let {
                LightningCodecs.writeBytes(it.encodeToByteArray(), out)
            }
        }

        companion object {
            val minLength: Int get() = 67

            fun read(input: Input): V2 {
                val offerId = LightningCodecs.bytes(input, 32).byteVector32()
                val amount = LightningCodecs.bigSize(input).msat
                val preimage = LightningCodecs.bytes(input, 32).byteVector32()
                val createdAtSeconds = LightningCodecs.bigSize(input)
                val flags = LightningCodecs.byte(input).toByte()

                val hasExp   = (flags and 0b00001) != 0.toByte()
                val hasPKey  = (flags and 0b00010) != 0.toByte()
                val hasQnty  = (flags and 0b00100) != 0.toByte()
                val hasDesc  = (flags and 0b01000) != 0.toByte()
                val hasPNote = (flags and 0b10000) != 0.toByte()

                val relativeExpirySeconds = if (hasExp) { LightningCodecs.bigSize(input) } else { null }
                val payerKey = if (hasPKey) { PublicKey(LightningCodecs.bytes(input, 33)) } else { null }
                val quantity = if (hasQnty) { LightningCodecs.u64(input) } else { null }
                val description = if (hasDesc) {
                    val strLen = if (hasPNote) { LightningCodecs.bigSize(input).toInt() } else { input.availableBytes }
                    LightningCodecs.bytes(input, strLen).decodeToString()
                } else { null }
                val payerNote = if (hasPNote) {
                    if (input.availableBytes > 0) { LightningCodecs.bytes(input, input.availableBytes).decodeToString() } else { "" }
                } else { null }

                return V2(offerId, amount, preimage, createdAtSeconds, relativeExpirySeconds, description, payerKey, payerNote, quantity)
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
                2 -> V2.read(input)
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
                    val minimum = V1.minLength + 64
                    if (input.availableBytes < minimum) return null
                    val metadataSize = input.availableBytes - 64
                    val metadata = LightningCodecs.bytes(input, metadataSize)
                    val signature = LightningCodecs.bytes(input, 64).byteVector64()
                    // Note that the signature includes the version byte.
                    if (!Crypto.verifySignature(Crypto.sha256(pathId.take(1 + metadataSize)), signature, nodeId)) return null
                    // This call is safe since we verified that we have the right number of bytes and the signature was valid.
                    return V1.read(ByteArrayInput(metadata))
                }
                2 -> {
                    val minimum = V2.minLength + 64
                    if (input.availableBytes < minimum) return null
                    val metadataSize = input.availableBytes - 64
                    val metadata = LightningCodecs.bytes(input, metadataSize)
                    val signature = LightningCodecs.bytes(input, 64).byteVector64()
                    // Note that the signature includes the version byte.
                    if (!Crypto.verifySignature(Crypto.sha256(pathId.take(1 + metadataSize)), signature, nodeId)) return null
                    // This call is safe since we verified that we have the right number of bytes and the signature was valid.
                    return V2.read(ByteArrayInput(metadata))
                }
                else -> return null
            }
        }
    }
}
