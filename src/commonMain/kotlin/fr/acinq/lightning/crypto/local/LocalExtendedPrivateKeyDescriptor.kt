package fr.acinq.lightning.crypto.local

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.lightning.crypto.ExtendedPrivateKeyDescriptor
import fr.acinq.lightning.crypto.PrivateKeyDescriptor

interface LocalExtendedPrivateKeyDescriptor : ExtendedPrivateKeyDescriptor {
    override fun publicKey(): DeterministicWallet.ExtendedPublicKey {
        return DeterministicWallet.publicKey(instantiate())
    }

    override fun derivePrivateKey(index: Long): PrivateKeyDescriptor {
        return FromExtendedPrivateKeyDescriptor(this, index)
    }
}
