package fr.acinq.lightning.payment

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.crypto.ChaCha20Poly1305
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.LightningCodecs
import kotlin.experimental.and
import kotlin.experimental.or

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
    abstract val payerKey: PublicKey?
    abstract val payerNote: String?

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
    fun toPathId(nodeKey: PrivateKey): ByteVector = when (this) {
        is V1 -> {
            val encoded = this.encode()
            val signature = Crypto.sign(Crypto.sha256(encoded), nodeKey)
            encoded + signature
        }
        is V2 -> {
            // We only encrypt what comes after the version byte.
            val encoded = run {
                val out = ByteArrayOutput()
                this.write(out)
                out.toByteArray()
            }
            val (encrypted, mac) = run {
                val paymentHash = Crypto.sha256(this.preimage).byteVector32()
                val metadataKey = V2.deriveKey(nodeKey, paymentHash)
                val nonce = paymentHash.take(12).toByteArray()
                ChaCha20Poly1305.encrypt(metadataKey.value.toByteArray(), nonce, encoded, paymentHash.toByteArray())
            }
            val out = ByteArrayOutput()
            out.write(2) // version
            out.write(encrypted)
            out.write(mac)
            out.toByteArray().byteVector()
        }
    }

    /** In this first version, we simply sign the payment metadata to verify its authenticity when receiving the payment. */
    data class V1(
        override val offerId: ByteVector32,
        override val amount: MilliSatoshi,
        override val preimage: ByteVector32,
        override val payerKey: PublicKey,
        override val payerNote: String?,
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

    /** In this version, we encrypt the payment metadata with a key derived from our seed. */
    data class V2(
        override val offerId: ByteVector32,
        override val amount: MilliSatoshi,
        override val preimage: ByteVector32,
        override val createdAtSeconds: Long,
        override val relativeExpirySeconds: Long?,
        val description: String?,
        override val payerKey: PublicKey?,
        override val payerNote: String?,
        val quantity: Long?,
    ) : OfferPaymentMetadata() {
        override val version: Byte get() = 2

        fun write(out: Output) {
            LightningCodecs.writeBytes(offerId, out)
            LightningCodecs.writeBigSize(amount.toLong(), out)
            LightningCodecs.writeBytes(preimage, out)
            LightningCodecs.writeBigSize(createdAtSeconds, out)
            // We use bit flags to track which nullable fields are provided.
            var flags: Byte = 0
            if (relativeExpirySeconds != null) flags = flags or 0b00001
            if (payerKey != null) flags = flags or 0b00010
            if (quantity != null) flags = flags or 0b00100
            if (description != null) flags = flags or 0b01000
            if (payerNote != null) flags = flags or 0b10000
            LightningCodecs.writeByte(flags.toInt(), out)

            relativeExpirySeconds?.let { LightningCodecs.writeBigSize(it, out) }
            payerKey?.let { LightningCodecs.writeBytes(it.value, out) }
            quantity?.let { LightningCodecs.writeBigSize(it, out) }
            description?.let {
                // If we have both a description and a payer note, we need to encode the size
                // to know when the payer note starts.
                val encodedDescription = it.encodeToByteArray()
                if (payerNote != null) LightningCodecs.writeBigSize(encodedDescription.size.toLong(), out)
                LightningCodecs.writeBytes(encodedDescription, out)
            }
            payerNote?.let { LightningCodecs.writeBytes(it.encodeToByteArray(), out) }
        }

        companion object {
            fun read(input: Input): V2 {
                val offerId = LightningCodecs.bytes(input, 32).byteVector32()
                val amount = LightningCodecs.bigSize(input).msat
                val preimage = LightningCodecs.bytes(input, 32).byteVector32()
                val createdAtSeconds = LightningCodecs.bigSize(input)
                // Bit flags indicate which fields are provided.
                val flags = LightningCodecs.byte(input).toByte()
                val hasRelativeExpiry = (flags and 0b00001) != 0.toByte()
                val hasPayerKey = (flags and 0b00010) != 0.toByte()
                val hasQuantity = (flags and 0b00100) != 0.toByte()
                val hasDescription = (flags and 0b01000) != 0.toByte()
                val hasPayerNote = (flags and 0b10000) != 0.toByte()
                // We can now read nullable fields.
                val relativeExpirySeconds = if (hasRelativeExpiry) LightningCodecs.bigSize(input) else null
                val payerKey = if (hasPayerKey) PublicKey(LightningCodecs.bytes(input, 33)) else null
                val quantity = if (hasQuantity) LightningCodecs.bigSize(input) else null
                val description = when {
                    hasDescription && hasPayerNote -> LightningCodecs.bytes(input, LightningCodecs.bigSize(input).toInt()).decodeToString()
                    hasDescription -> LightningCodecs.bytes(input, input.availableBytes).decodeToString()
                    else -> null
                }
                val payerNote = if (hasPayerNote) LightningCodecs.bytes(input, input.availableBytes).decodeToString() else null
                return V2(offerId, amount, preimage, createdAtSeconds, relativeExpirySeconds, description, payerKey, payerNote, quantity)
            }

            fun deriveKey(nodeKey: PrivateKey, paymentHash: ByteVector32): PrivateKey {
                val tweak = Crypto.sha256("offer_payment_metadata_v2".encodeToByteArray() + paymentHash.toByteArray() + nodeKey.value.toByteArray())
                return nodeKey * PrivateKey(tweak)
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
        fun fromPathId(nodeKey: PrivateKey, pathId: ByteVector, paymentHash: ByteVector32): OfferPaymentMetadata? {
            if (pathId.isEmpty()) return null
            val input = ByteArrayInput(pathId.toByteArray())
            return when (LightningCodecs.byte(input)) {
                1 -> {
                    if (input.availableBytes < 185) return null
                    val metadataSize = input.availableBytes - 64
                    val metadata = LightningCodecs.bytes(input, metadataSize)
                    val signature = LightningCodecs.bytes(input, 64).byteVector64()
                    // Note that the signature includes the version byte.
                    if (!Crypto.verifySignature(Crypto.sha256(pathId.take(1 + metadataSize)), signature, nodeKey.publicKey())) return null
                    // This call is safe since we verified that we have the right number of bytes and the signature was valid.
                    V1.read(ByteArrayInput(metadata))
                }
                2 -> {
                    val metadataKey = V2.deriveKey(nodeKey, paymentHash)
                    val nonce = paymentHash.take(12).toByteArray()
                    val encryptedSize = input.availableBytes - 16
                    try {
                        val encrypted = LightningCodecs.bytes(input, encryptedSize)
                        val mac = LightningCodecs.bytes(input, 16)
                        val decrypted = ChaCha20Poly1305.decrypt(metadataKey.value.toByteArray(), nonce, encrypted, paymentHash.toByteArray(), mac)
                        V2.read(ByteArrayInput(decrypted))
                    } catch (_: Throwable) {
                        null
                    }
                }
                else -> null
            }
        }

        /** Truncate a string to at most the provided [len], including an additional "…" character. */
        private fun truncateNote(str: String, len: Int): String = when {
            // The ellipsis "…" character uses 3-bytes when encoded as UTF-8.
            str.encodeToByteArray().size <= len - 3 -> "$str…"
            // We don't know how many bytes the last characters use in UTF-8, so we simply recursively remove characters.
            else -> truncateNote(str.dropLast(1), len)
        }

        /** Truncates the offer description and payer note, where the combined size (in UTF-8) is guaranteed to be <= 64 bytes. */
        fun truncateNotes(payerNote: String?, description: String?): Pair<String?, String?> {
            val payerNoteLen = payerNote?.encodeToByteArray()?.size ?: 0
            val descriptionLen = description?.encodeToByteArray()?.size ?: 0
            return when {
                // If strings are below the size limit, we don't need to do anything.
                payerNoteLen + descriptionLen <= 64 -> Pair(payerNote, description)
                // If only one string is provided, this is simple, we directly truncate it to at most 64 bytes.
                payerNote == null || description == null -> Pair(payerNote?.let { truncateNote(it, 64) }, description?.let { truncateNote(it, 64) })
                // If both strings exceed 32 bytes, we truncate both to at most 32 bytes each.
                payerNoteLen > 32 && descriptionLen > 32 -> Pair(truncateNote(payerNote, 32), truncateNote(description, 32))
                // Otherwise, we only need to truncate the largest one.
                payerNoteLen < descriptionLen -> Pair(payerNote, truncateNote(description, 64 - payerNoteLen))
                else -> Pair(truncateNote(payerNote, 64 - descriptionLen), description)
            }
        }
    }
}
