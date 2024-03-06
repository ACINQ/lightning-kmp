package fr.acinq.lightning.crypto.local

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.lightning.crypto.ExtendedPrivateKeyDescriptor
import fr.acinq.lightning.crypto.PrivateKeyDescriptor

class DerivedExtendedPrivateKeyDescriptor(
    private val parent: ExtendedPrivateKeyDescriptor,
    private val keyPath: KeyPath
) : LocalExtendedPrivateKeyDescriptor {
    override fun instantiate(): DeterministicWallet.ExtendedPrivateKey {
        return DeterministicWallet.derivePrivateKey(parent.instantiate(), keyPath)
    }
}