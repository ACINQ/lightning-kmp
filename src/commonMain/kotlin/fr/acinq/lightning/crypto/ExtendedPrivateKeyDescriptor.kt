package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PrivateKey

interface ExtendedPrivateKeyDescriptor {

    fun privateKey(): PrivateKeyDescriptor

    fun publicKey(): DeterministicWallet.ExtendedPublicKey

    fun derive(path: KeyPath): ExtendedPrivateKeyDescriptor

    fun derivePrivateKey(index: Long): PrivateKeyDescriptor

    fun derivePrivateKey(path: KeyPath): PrivateKeyDescriptor
}