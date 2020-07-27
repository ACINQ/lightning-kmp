package fr.acinq.eklair.blockchain.bitcoind

import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Transaction
import kotlin.test.assertEquals

object BitcoindService {
    private val client = BitcoinJsonRPCClient()

    suspend fun getNewAddress(): Pair<String, PrivateKey> {
        val address=  client.sendRequest<GetNewAddressResponse>(GetNewAddress).address
        val privateKey = getPrivateKey(address)
        return address to privateKey
    }
    suspend fun getPrivateKey(address: String): PrivateKey = client.sendRequest<DumpPrivateKeyResponse>(DumpPrivateKey(address)).privateKey
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

    fun close() = client.close()
}