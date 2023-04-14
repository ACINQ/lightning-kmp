package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey

/**
 * BOLT 3 Key derivation scheme.
 */
object Bolt3Derivation {

    fun perCommitSecret(seed: ByteVector32, index: Long): PrivateKey = PrivateKey(ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFL - index))

    fun perCommitPoint(seed: ByteVector32, index: Long): PublicKey = perCommitSecret(seed, index).publicKey()

    private fun derivePrivKey(secret: PrivateKey, perCommitPoint: PublicKey): PrivateKey {
        // secretkey = basepoint-secret + SHA256(per-commitment-point || basepoint)
        return secret + (PrivateKey(sha256(perCommitPoint.value + secret.publicKey().value)))
    }

    fun PrivateKey.derive(perCommitPoint: PublicKey): PrivateKey = derivePrivKey(this, perCommitPoint)

    private fun derivePubKey(basePoint: PublicKey, perCommitPoint: PublicKey): PublicKey {
        //pubkey = basepoint + SHA256(per-commitment-point || basepoint)*G
        val a = PrivateKey(sha256(perCommitPoint.value + basePoint.value))
        return basePoint + a.publicKey()
    }

    fun PublicKey.derive(perCommitPoint: PublicKey): PublicKey = derivePubKey(this, perCommitPoint)

    private fun revocationPubKey(basePoint: PublicKey, perCommitPoint: PublicKey): PublicKey {
        val a = PrivateKey(sha256(basePoint.value + perCommitPoint.value))
        val b = PrivateKey(sha256(perCommitPoint.value + basePoint.value))
        return (basePoint * a) + (perCommitPoint * b)
    }

    fun PublicKey.deriveRevocation(perCommitPoint: PublicKey): PublicKey = revocationPubKey(this, perCommitPoint)

    private fun revocationPrivKey(secret: PrivateKey, perCommitSecret: PrivateKey): PrivateKey {
        val a = PrivateKey(sha256(secret.publicKey().value + perCommitSecret.publicKey().value))
        val b = PrivateKey(sha256(perCommitSecret.publicKey().value + secret.publicKey().value))
        return (secret * a) + (perCommitSecret * b)
    }

    fun PrivateKey.deriveRevocation(perCommitSecret: PrivateKey): PrivateKey = revocationPrivKey(this, perCommitSecret)

}
