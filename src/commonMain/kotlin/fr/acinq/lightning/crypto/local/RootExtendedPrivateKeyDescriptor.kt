package fr.acinq.lightning.crypto.local

import fr.acinq.bitcoin.DeterministicWallet

class RootExtendedPrivateKeyDescriptor(val master: DeterministicWallet.ExtendedPrivateKey) :
    LocalExtendedPrivateKeyDescriptor {
    override fun instantiate(): DeterministicWallet.ExtendedPrivateKey {
        return master
    }
}