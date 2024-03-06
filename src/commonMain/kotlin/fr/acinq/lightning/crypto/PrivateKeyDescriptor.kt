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

    // TODO: this function is only used for keys generated from revocation
    //       derivation so, maybe, we could make it so that only this type
    //       of keys can be derived that way.
    fun deriveForRevocation(perCommitSecret: PrivateKey): PrivateKeyDescriptor

    // TODO: this function is only used for htlc and delayed payment keys
    //       so, maybe, we could make it so that only this type of keys can
    //       be derived that way.
    fun deriveForCommitment(perCommitPoint: PublicKey): PrivateKeyDescriptor
}
