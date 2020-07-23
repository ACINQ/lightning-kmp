package fr.acinq.eklair.blockchain.bitcoind

import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Transaction
import kotlin.test.assertEquals

object BitcoindService {
    private val client = BitcoinJsonRPCClient()

    suspend fun getNewAddress(): String = client.sendRequest<GetNewAddressResponse>(GetNewAddress).address
    suspend fun getPrivateKey(address: String): PrivateKey = client.sendRequest<DumpPrivateKeyResponse>(DumpPrivateKey(address)).privateKey
    suspend fun sendToAddress(address: String, amount: Double): Transaction {
        val sendToAddress : SendToAddressResponse = client.sendRequest(SendToAddress(address, amount))
        val transaction = client.sendRequest<GetRawTransactionResponse>(GetRawTransaction(sendToAddress.txid))
        return transaction.tx
    }
    suspend fun generateBlocks(blockCount: Int) {
        val address = getNewAddress()
        val blocks = client.sendRequest<GenerateToAddressReponse>(GenerateToAddress(blockCount, address)).blocks
        assertEquals(blockCount, blocks.size)
    }
}