package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.transactions.Transactions

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

    /**
     * sign a tx input
     *
     * @param tx                   input transaction
     * @param inputIndex           index of the tx input that is being processed
     * @param redeemScript         public key script of the output claimed by this tx input
     * @param amount               amount of the output claimed by this tx input
     * @param sighash              signature hash type, which will be appended to the signature
     * @return the encoded signature of this tx for this specific tx input
     */
    fun sign(tx: Transaction, inputIndex: Int, redeemScript: ByteArray, amount: Satoshi, sighash: Int = SigHash.SIGHASH_ALL): ByteVector64

    /**
     * sign a tx input
     *
     * transaction must spend the input to sign
     *
     * @param txInfo               input transaction
     * @param sighash              signature hash type, which will be appended to the signature
     * @return the encoded signature of this tx
     */
    fun sign(txInfo: Transactions.TransactionWithInputInfo, sighash: Int = SigHash.SIGHASH_ALL): ByteVector64

    /**
     * Sign a taproot tx input, using one of its script paths.
     *
     * @param tx input transaction.
     * @param inputIndex index of the tx input that is being signed.
     * @param inputs list of all UTXOs spent by this transaction.
     * @param sighash signature hash type, which will be appended to the signature (if not default).
     * @param tapleaf tapscript leaf hash of the script that is being spent.
     * @return the schnorr signature of this tx for this specific tx input and the given script leaf.
     */
    fun signInputTaprootScriptPath(tx: Transaction, inputIndex: Int, inputs: List<TxOut>, sigHash: Int, tapleaf: ByteVector32): ByteVector64
}
