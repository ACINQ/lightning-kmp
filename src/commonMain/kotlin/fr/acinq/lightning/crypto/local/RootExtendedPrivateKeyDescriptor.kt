package fr.acinq.lightning.crypto.local

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.lightning.crypto.ExtendedPrivateKeyDescriptor
import fr.acinq.lightning.crypto.PrivateKeyDescriptor

class RootExtendedPrivateKeyDescriptor(val master: DeterministicWallet.ExtendedPrivateKey) :
    LocalExtendedPrivateKeyDescriptor {
    override fun instantiate(): DeterministicWallet.ExtendedPrivateKey {
        return master
    }
}