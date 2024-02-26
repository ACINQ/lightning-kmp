package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey

interface PrivateKeyDescriptor {
    // TODO: instantiate function should be removed from this interface
    //  and become private at some point. Only the keymanager that supports
    //  a given type of keys should be able to manipulate them to sign with.
    //  But for now, we keep it public as the signing is done directly in
    //  Transactions.kt
    abstract fun instantiate(): PrivateKey

    fun publicKey(): PublicKey
}
