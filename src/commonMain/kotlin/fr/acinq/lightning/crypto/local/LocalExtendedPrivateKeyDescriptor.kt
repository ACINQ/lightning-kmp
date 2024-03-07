package fr.acinq.lightning.crypto.local

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.lightning.crypto.ExtendedPrivateKeyDescriptor
import fr.acinq.lightning.crypto.PrivateKeyDescriptor

interface LocalExtendedPrivateKeyDescriptor : ExtendedPrivateKeyDescriptor {
    fun instantiate(): DeterministicWallet.ExtendedPrivateKey

    override fun privateKey(): PrivateKeyDescriptor {
        return FromExtendedPrivateKeyDescriptor(this)
    }

    override fun publicKey(): DeterministicWallet.ExtendedPublicKey {
        return DeterministicWallet.publicKey(instantiate())
    }

    override fun derive(path: KeyPath): ExtendedPrivateKeyDescriptor {
        return DerivedExtendedPrivateKeyDescriptor(this, path)
    }

    override fun derivePrivateKey(index: Long): PrivateKeyDescriptor {
        return FromExtendedPrivateKeyDescriptor(this, index)
    }

    override fun derivePrivateKey(path: KeyPath): PrivateKeyDescriptor {
        return FromExtendedPrivateKeyDescriptor(this, path)
    }
}
