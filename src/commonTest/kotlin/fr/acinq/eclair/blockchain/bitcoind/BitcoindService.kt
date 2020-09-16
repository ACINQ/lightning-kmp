package fr.acinq.eclair.blockchain.bitcoind

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.SigHash.SIGHASH_ALL
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.utils.toByteVector
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.json.JsonElement
import kotlin.math.pow
import kotlin.test.assertEquals

class BitcoindService {
    private val client = BitcoinJsonRPCClient()

    suspend fun getNetworkInfo(): JsonElement = client.sendRequest<GetNetworkInfoResponse>(GetNetworkInfo).result
    suspend fun getBlockCount(): Int = client.sendRequest<GetBlockCountResponse>(GetBlockCount).blockcount
    suspend fun getRawMempool(): List<String> = client.sendRequest<GetRawMempoolResponse>(GetRawMempool).txids

    suspend fun getNewAddress(): Pair<String, PrivateKey> {
        val address=  client.sendRequest<GetNewAddressResponse>(GetNewAddress).address
        val privateKey = getPrivateKey(address)
        return address to privateKey
    }
    suspend fun getPrivateKey(address: String): PrivateKey {
        return client.sendRequest<DumpPrivateKeyResponse>(DumpPrivateKey(address)).privateKey
    }
    suspend fun sendToAddress(address: String, amount: Double): Transaction {
        val sendToAddress : SendToAddressResponse = client.sendRequest(SendToAddress(address, amount))
        val transaction = client.sendRequest<GetRawTransactionResponse>(GetRawTransaction(sendToAddress.txid))
        return transaction.tx
    }
    suspend fun generateBlocks(blockCount: Int) {
        val (address,_) = getNewAddress()
        val blocks = client.sendRequest<GenerateToAddressResponse>(GenerateToAddress(blockCount, address)).blocks
        assertEquals(blockCount, blocks.size)
    }

    suspend fun sendRawTransaction(tx: Transaction): Transaction {
        val sendRawTransactionResponse = client.sendRequest<SendRawTransactionResponse>(SendRawTransaction(tx))
        val transaction = client.sendRequest<GetRawTransactionResponse>(GetRawTransaction(sendRawTransactionResponse.txid))
        return transaction.tx
    }

    suspend fun fundTransaction(tx: Transaction, lockUnspents: Boolean, feeRatePerKw: Satoshi): Transaction {
        val response = client.sendRequest<FundTransactionResponse>(FundTransaction(
            Hex.encode(Transaction.write(tx)),
            lockUnspents, (feeRatePerKw.sat * 4).toDouble() * 0.00000001
        ))
        return Transaction.read(response.hex)
    }

    suspend fun signTransaction(tx: Transaction): Transaction {
        return Transaction.read(client.sendRequest<SignTransactionResponse>(SignTransaction(tx)).hex)
    }

    fun createUnspentTxChain(tx: Transaction, privateKey: PrivateKey): Pair<Transaction, Transaction> {
        // tx sends funds to our key
        val publicKey = privateKey.publicKey()
        val outputIndex = tx.txOut.indexOfFirst { it.publicKeyScript == Script.write(Script.pay2wpkh(publicKey)).byteVector() }
        val fee = 10000.sat

        // tx1 spends tx
        val tx1 = kotlin.run {
            val tmp = Transaction(version = 2,
                txIn = listOf(TxIn(OutPoint(tx, outputIndex.toLong()), signatureScript = emptyList(), sequence = TxIn.SEQUENCE_FINAL)),
                txOut = listOf(TxOut(tx.txOut[outputIndex].amount - fee, publicKeyScript = Script.pay2wpkh(publicKey))),
                lockTime = 0)

            val sig = Transaction.signInput(
                tmp,
                0,
                Script.pay2pkh(publicKey),
                SigHash.SIGHASH_ALL,
                tx.txOut[outputIndex].amount,
                SigVersion.SIGVERSION_WITNESS_V0,
                privateKey
            ).byteVector()
            val signedTx = tmp.updateWitness(0, ScriptWitness(listOf(sig, publicKey.value)))
            Transaction.correctlySpends(signedTx, listOf(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            signedTx
        }

        // and tx2 spends tx1
        val tx2 = kotlin.run {
            val tmp = Transaction(version = 2,
                txIn = listOf(TxIn(OutPoint(tx1, 0), signatureScript = emptyList(), sequence = TxIn.SEQUENCE_FINAL)),
                txOut = listOf(TxOut(tx1.txOut.first().amount - fee, publicKeyScript = Script.pay2wpkh(publicKey))),
                lockTime = 0)

            val sig = Transaction.signInput(
                tmp,
                0,
                Script.pay2pkh(publicKey),
                SigHash.SIGHASH_ALL,
                tx1.txOut.first().amount,
                SigVersion.SIGVERSION_WITNESS_V0,
                privateKey
            ).byteVector()
            val signedTx = tmp.updateWitness(0, ScriptWitness(listOf(sig, publicKey.value)))
            Transaction.correctlySpends(signedTx, listOf(tx1), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            signedTx
        }

        return tx1 to tx2
    }


    fun createTx(tx: Transaction, privateKey: PrivateKey): Pair<Transaction, Transaction> {
        // tx sends funds to our key
        val publicKey = privateKey.publicKey()
        val outputIndex = tx.txOut.indexOfFirst { it.publicKeyScript == Script.write(Script.pay2wpkh(publicKey)).byteVector() }
        val fee = 10000.sat

        // tx1 spends tx
        val tx1 = kotlin.run {
            val tmp = Transaction(version = 2,
                txIn = listOf(TxIn(OutPoint(tx, outputIndex.toLong()), signatureScript = emptyList(), sequence = TxIn.SEQUENCE_LOCKTIME_DISABLE_FLAG)),
                txOut = listOf(TxOut(tx.txOut[outputIndex].amount - fee, publicKeyScript = Script.pay2wpkh(publicKey))),
                lockTime = 200)

            val sig = Transaction.signInput(
                tmp,
                0,
                Script.pay2pkh(publicKey),
                SigHash.SIGHASH_ALL,
                tx.txOut[outputIndex].amount,
                SigVersion.SIGVERSION_WITNESS_V0,
                privateKey
            ).byteVector()
            val signedTx = tmp.updateWitness(0, ScriptWitness(listOf(sig, publicKey.value)))
            Transaction.correctlySpends(signedTx, listOf(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            signedTx
        }

        // and tx2 spends tx1
        val tx2 = kotlin.run {
            val tmp = Transaction(version = 2,
                txIn = listOf(TxIn(OutPoint(tx1, 0), signatureScript = emptyList(), sequence = TxIn.SEQUENCE_LOCKTIME_MASK)),
                txOut = listOf(TxOut(tx1.txOut.first().amount - fee, publicKeyScript = Script.pay2wpkh(publicKey))),
                lockTime = 175)

            val sig = Transaction.signInput(
                tmp,
                0,
                Script.pay2pkh(publicKey),
                SigHash.SIGHASH_ALL,
                tx1.txOut.first().amount,
                SigVersion.SIGVERSION_WITNESS_V0,
                privateKey
            ).byteVector()
            val signedTx = tmp.updateWitness(0, ScriptWitness(listOf(sig, publicKey.value)))
            Transaction.correctlySpends(signedTx, listOf(tx1), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            signedTx
        }

        return tx1 to tx2
    }

    fun createSpendP2WPKH(
        parentTx: Transaction,
        privateKey: PrivateKey,
        to: PublicKey,
        fee: Satoshi,
        sequence: Long,
        lockTime: Long
    ): Transaction {
        // tx sends funds to our key
        val publicKey = privateKey.publicKey()
        val outputIndex = parentTx.txOut.indexOfFirst { it.publicKeyScript == Script.write(Script.pay2wpkh(publicKey)).byteVector() }
        // we spend this output and create a similar output with a smaller fee
        val unsigned = Transaction(
            version = 2,
            txIn = listOf(TxIn(OutPoint(parentTx, outputIndex.toLong()), sequence)),
            txOut = listOf(TxOut(parentTx.txOut[outputIndex].amount - fee, publicKeyScript = Script.pay2wpkh(to))), lockTime = lockTime)

        val sig = Transaction.signInput(
            unsigned,
            0,
            Script.pay2pkh(publicKey),
            SIGHASH_ALL,
            parentTx.txOut[outputIndex].amount,
            SigVersion.SIGVERSION_WITNESS_V0,
            privateKey
        ).toByteVector()

        val signedTx = unsigned.updateWitness(0, ScriptWitness(listOf(sig, publicKey.value)))
        Transaction.correctlySpends(signedTx, listOf(parentTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        return signedTx
    }
}
