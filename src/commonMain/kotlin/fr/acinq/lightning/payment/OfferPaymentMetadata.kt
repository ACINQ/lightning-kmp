package fr.acinq.lightning.payment

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.LightningCodecs

data class OfferPaymentMetadata(
    val offerId: ByteVector32,
    val preimage: ByteVector32,
    val payerKey: PublicKey,
    val createdAtMillis: Long,
    val quantity: Long,
    val amount: MilliSatoshi,
    val data: ByteVector) {

    fun write(privateKey: PrivateKey, out: Output) {
        val tmp = ByteArrayOutput()
        LightningCodecs.writeBytes(offerId, tmp)
        LightningCodecs.writeBytes(preimage, tmp)
        LightningCodecs.writeBytes(payerKey.value, tmp)
        LightningCodecs.writeU64(createdAtMillis, tmp)
        LightningCodecs.writeU64(quantity, tmp)
        LightningCodecs.writeU64(amount.toLong(), tmp)
        LightningCodecs.writeBytes(data, tmp)
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
            require(Crypto.verifySignature(Crypto.sha256(metadata), signature, publicKey))
            val metadataInput = ByteArrayInput(metadata)
            return OfferPaymentMetadata(
                ByteVector32(LightningCodecs.bytes(metadataInput, 32)),
                ByteVector32(LightningCodecs.bytes(metadataInput, 32)),
                PublicKey(LightningCodecs.bytes(metadataInput, 33)),
                LightningCodecs.u64(metadataInput),
                LightningCodecs.u64(metadataInput),
                MilliSatoshi(LightningCodecs.u64(metadataInput)),
                LightningCodecs.bytes(metadataInput, metadataInput.availableBytes).toByteVector()
            )
        }
    }
}