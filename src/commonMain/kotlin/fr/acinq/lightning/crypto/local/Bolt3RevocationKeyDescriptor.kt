package fr.acinq.lightning.crypto.local

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.crypto.PrivateKeyDescriptor

class Bolt3RevocationKeyDescriptor(
    private val parent: LocalPrivateKeyDescriptor,
    private val perCommitSecret: PrivateKey
) : LocalPrivateKeyDescriptor {
    override fun instantiate(): PrivateKey {
        return revocationPrivKey(parent.instantiate(), perCommitSecret)
    }

    private fun revocationPrivKey(secret: PrivateKey, perCommitSecret: PrivateKey): PrivateKey {
        val a = PrivateKey(Crypto.sha256(secret.publicKey().value + perCommitSecret.publicKey().value))
        val b = PrivateKey(Crypto.sha256(perCommitSecret.publicKey().value + secret.publicKey().value))
        return (secret * a) + (perCommitSecret * b)
    }
}