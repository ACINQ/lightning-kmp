package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.PrivateKey

interface ExtendedPrivateKeyDescriptor {
    // TODO: instantiate function should be removed from this interface
    //  and become private at some point. Only the keymanager that supports
    //  a given type of keys should be able to manipulate them to sign with.
    //  But for now, we keep it public as its needed in different places.
    fun instantiate(): DeterministicWallet.ExtendedPrivateKey

    fun publicKey(): DeterministicWallet.ExtendedPublicKey

    fun derivePrivateKey(index: Long): PrivateKeyDescriptor
}