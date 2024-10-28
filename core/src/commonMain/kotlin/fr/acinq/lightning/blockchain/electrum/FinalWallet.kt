package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.logging.info
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.sum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class FinalWallet(
    private val chain: Chain,
    private val finalWalletKeys: KeyManager.Bip84OnChainKeys,
    electrum: IElectrumClient,
    scope: CoroutineScope,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.newLogger(this::class)

    val wallet = ElectrumMiniWallet(chain.chainHash, electrum, scope, logger)
    private val addressIndex = 0L
    val finalAddress: String = finalWalletKeys.address(addressIndex = addressIndex).also { wallet.addAddress(it) }

    init {
        scope.launch {
            wallet.walletStateFlow
                .distinctUntilChangedBy { it.totalBalance }
                .collect { wallet ->
                    logger.info { "${wallet.totalBalance} available on final wallet with ${wallet.utxos.size} utxos" }
                }
        }
    }

    /** Builds a tx that spends all confirmed available funds and send them to a given address. Does not sign or broadcast it. */
    fun buildSendAllTransaction(bitcoinAddress: String, feerate: FeeratePerKw): Pair<Transaction, Satoshi>? {
        val pubkeyScript = Bitcoin.addressToPublicKeyScript(chain.chainHash, bitcoinAddress).right ?: return null

        val utxos = wallet.walletStateFlow.value.utxos.filter { it.blockHeight > 0 }

        fun buildTx(amount: Satoshi) = Transaction(
            version = 2,
            txIn = utxos.map { TxIn(it.outPoint, sequence = 0xffffffffL) },
            txOut = listOf(TxOut(amount, pubkeyScript)),
            lockTime = 0
        )

        // build and sign a dummy tx to compute the fee
        val signedDummyTx = signTransaction(buildTx(0.sat)) ?: return null
        val fee = Transactions.weight2fee(feerate, signedDummyTx.weight())
        val amountMinusFee = utxos.map { it.amount }.sum() - fee
        if (amountMinusFee < 546.sat) return null

        return buildTx(amountMinusFee) to fee
    }

    /** Sign the given input if we have the corresponding private key (only works for P2WPKH scripts). */
    private fun signInput(tx: Transaction, inputIndex: Int, utxo: WalletState.Utxo): Transaction {
        val privateKey = finalWalletKeys.privateKey(0)
        // mind this: the pubkey script used for signing is not the prevout pubscript (which is just a push
        // of the pubkey hash), but the actual script that is evaluated by the script engine, in this case a PAY2PKH script
        val publicKey = privateKey.publicKey()
        val pubKeyScript = Script.pay2pkh(publicKey)
        val sig = Transaction.signInput(tx, inputIndex, pubKeyScript, SigHash.SIGHASH_ALL, utxo.amount, SigVersion.SIGVERSION_WITNESS_V0, privateKey)
        val witness = Script.witnessPay2wpkh(publicKey, sig.byteVector())
        return tx.updateWitness(inputIndex, witness)
    }

    fun signTransaction(unsignedTx: Transaction): Transaction? {
        return unsignedTx.txIn.withIndex().fold(unsignedTx) { partiallySignedTx, txIn ->
            val utxo = wallet.walletStateFlow.value.utxos.find { it.outPoint == txIn.value.outPoint } ?: return null
            signInput(partiallySignedTx, inputIndex = txIn.index, utxo)
        }
    }

}