package fr.acinq.lightning.crypto.local

import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.crypto.PrivateKeyDescriptor

interface LocalPrivateKeyDescriptor : PrivateKeyDescriptor {

    override fun publicKey(): PublicKey {
        return instantiate().publicKey()
    }

    override fun deriveForRevocation(perCommitSecret: PrivateKey): PrivateKeyDescriptor =
        Bolt3RevocationKeyDescriptor(this, perCommitSecret)

    override fun deriveForCommitment(perCommitPoint: PublicKey): PrivateKeyDescriptor =
        Bolt3CommitmentKeyDescriptor(this, perCommitPoint)
}
