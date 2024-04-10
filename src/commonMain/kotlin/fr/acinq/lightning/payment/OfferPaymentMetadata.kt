package fr.acinq.lightning.payment

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.LightningCodecs

/**
 * OfferPaymentMetadata is an alternative to storing a Bolt12Invoice in a database which would expose us to DoS.
 * It contains the important bits from the invoice that we need to validate an incoming payment and is given to the
 * payer signed and encrypted. When making the payment, the OfferPaymentMetadata will be in the pathId and will allow us
 * to know what the payment is for and validate it.
 */
data class OfferPaymentMetadata(
    val offerId: ByteVector32,
    val preimage: ByteVector32,
    val payerKey: PublicKey,
    val amount: MilliSatoshi,
    val quantity: Long,
    val createdAtMillis: Long) {

    fun write(privateKey: PrivateKey, out: Output) {
        val tmp = ByteArrayOutput()
        LightningCodecs.writeBytes(offerId, tmp)
        LightningCodecs.writeBytes(preimage, tmp)
        LightningCodecs.writeBytes(payerKey.value, tmp)
        LightningCodecs.writeU64(amount.toLong(), tmp)
        LightningCodecs.writeU64(quantity, tmp)
        LightningCodecs.writeU64(createdAtMillis, tmp)
        val metadata = tmp.toByteArray()
        val signature = Crypto.sign(Crypto.sha256(metadata), privateKey)
        LightningCodecs.writeBytes(signature, out)
        LightningCodecs.writeBytes(metadata, out)
    }

    fun write(privateKey: PrivateKey): ByteVector {
        val tmp = ByteArrayOutput()
        write(privateKey, tmp)
        return tmp.toByteArray().toByteVector()
    }

    companion object {
        fun read(publicKey: PublicKey, input: Input): OfferPaymentMetadata {
            val signature = ByteVector64(LightningCodecs.bytes(input, 64))
            val metadata = LightningCodecs.bytes(input, input.availableBytes)
            // We verify the signature to ensure that we generated a matching invoice and not someone else.
            require(Crypto.verifySignature(Crypto.sha256(metadata), signature, publicKey))
            val metadataInput = ByteArrayInput(metadata)
            return OfferPaymentMetadata(
                ByteVector32(LightningCodecs.bytes(metadataInput, 32)),
                ByteVector32(LightningCodecs.bytes(metadataInput, 32)),
                PublicKey(LightningCodecs.bytes(metadataInput, 33)),
                MilliSatoshi(LightningCodecs.u64(metadataInput)),
                LightningCodecs.u64(metadataInput),
                LightningCodecs.u64(metadataInput))
        }
    }
}