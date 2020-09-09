package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eclair.utils.leftPaddedCopyOf

object Generators {

    fun fixSize(data: ByteArray): ByteVector32 = when {
        data.size == 32 -> ByteVector32(data)
        data.size < 32 -> ByteVector32(data.leftPaddedCopyOf(32))
        else -> error("Data is too big")
    }

    fun perCommitSecret(seed: ByteVector32, index: Long): PrivateKey = PrivateKey(ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFL - index))

    fun perCommitPoint(seed: ByteVector32, index: Long): PublicKey = perCommitSecret(seed, index).publicKey()

    fun derivePrivKey(secret: PrivateKey, perCommitPoint: PublicKey): PrivateKey {
        // secretkey = basepoint-secret + SHA256(per-commitment-point || basepoint)
        return secret + (PrivateKey(sha256(perCommitPoint.value + secret.publicKey().value)))
    }

    fun derivePubKey(basePoint: PublicKey, perCommitPoint: PublicKey): PublicKey {
        //pubkey = basepoint + SHA256(per-commitment-point || basepoint)*G
        val a = PrivateKey(sha256(perCommitPoint.value + basePoint.value))
        return basePoint + a.publicKey()
    }

    fun revocationPubKey(basePoint: PublicKey, perCommitPoint: PublicKey): PublicKey {
        val a = PrivateKey(sha256(basePoint.value + perCommitPoint.value))
        val b = PrivateKey(sha256(perCommitPoint.value + basePoint.value))
        return (basePoint * a) + (perCommitPoint * b)
    }

    fun revocationPrivKey(secret: PrivateKey, perCommitSecret: PrivateKey): PrivateKey {
        val a = PrivateKey(sha256(secret.publicKey().value + perCommitSecret.publicKey().value))
        val b = PrivateKey(sha256(perCommitSecret.publicKey().value + secret.publicKey().value))
        return (secret * a) + (perCommitSecret * b)
    }

}
