package fr.acinq.lightning.crypto.local

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.crypto.ExtendedPrivateKeyDescriptor
import fr.acinq.lightning.crypto.PrivateKeyDescriptor

class FromExtendedPrivateKeyDescriptor(private val parent: ExtendedPrivateKeyDescriptor, private val path: KeyPath) :
    LocalPrivateKeyDescriptor {

    constructor(parent: ExtendedPrivateKeyDescriptor, index: Long): this(parent,
        KeyPath(listOf(index))
    )

    constructor(parent: ExtendedPrivateKeyDescriptor): this(parent, KeyPath(listOf()))

    override fun instantiate(): PrivateKey {
        return DeterministicWallet.derivePrivateKey(parent.instantiate(), path).privateKey
    }
}