package fr.acinq.lightning.crypto.local

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey

class Bolt3CommitmentKeyDescriptor(
    private val parent: LocalPrivateKeyDescriptor,
    private val perCommitPoint: PublicKey
) : LocalPrivateKeyDescriptor {
    override fun instantiate(): PrivateKey {
        return derivePrivKey(parent.instantiate(), perCommitPoint)
    }

    private fun derivePrivKey(secret: PrivateKey, perCommitPoint: PublicKey): PrivateKey {
        // secretkey = basepoint-secret + SHA256(per-commitment-point || basepoint)
        return secret + (PrivateKey(Crypto.sha256(perCommitPoint.value + secret.publicKey().value)))
    }
}