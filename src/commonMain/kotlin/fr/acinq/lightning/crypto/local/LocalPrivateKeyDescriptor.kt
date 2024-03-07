package fr.acinq.lightning.crypto.local

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.SigVersion
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.crypto.PrivateKeyDescriptor
import fr.acinq.lightning.transactions.Transactions

interface LocalPrivateKeyDescriptor : PrivateKeyDescriptor {

    override fun publicKey(): PublicKey {
        return instantiate().publicKey()
    }

    override fun deriveForRevocation(perCommitSecret: PrivateKey): PrivateKeyDescriptor =
        Bolt3RevocationKeyDescriptor(this, perCommitSecret)

    override fun deriveForCommitment(perCommitPoint: PublicKey): PrivateKeyDescriptor =
        Bolt3CommitmentKeyDescriptor(this, perCommitPoint)

    override fun sign(tx: Transaction, inputIndex: Int, redeemScript: ByteArray, amount: Satoshi, sighash: Int): ByteVector64 {
        val key = instantiate()
        val sigDER = Transaction.signInput(tx, inputIndex, redeemScript, sighash, amount, SigVersion.SIGVERSION_WITNESS_V0, key)
        return Crypto.der2compact(sigDER)
    }

    override fun sign(txInfo: Transactions.TransactionWithInputInfo, sighash: Int): ByteVector64 {
        val inputIndex = txInfo.tx.txIn.indexOfFirst { it.outPoint == txInfo.input.outPoint }
        require(inputIndex >= 0) { "transaction doesn't spend the input to sign" }
        return sign(txInfo.tx, inputIndex, txInfo.input.redeemScript.toByteArray(), txInfo.input.txOut.amount, sighash)
    }

    override fun signInputTaprootScriptPath(tx: Transaction, inputIndex: Int, inputs: List<TxOut>, sigHash: Int, tapleaf: ByteVector32): ByteVector64 {
        return Transaction.signInputTaprootScriptPath(instantiate(), tx, inputIndex, inputs, SigHash.SIGHASH_DEFAULT, tapleaf)
    }
}
