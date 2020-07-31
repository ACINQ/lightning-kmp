package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.eklair.utils.toByteVector32
import kotlinx.coroutines.channels.SendChannel

interface ElectrumClient {

    fun start()
    fun stop()
    fun sendMessage(message: ElectrumMessage)
    fun sendElectrumRequest(request: ElectrumRequest, requestor: SendChannel<ElectrumMessage>? = null)

    companion object {
        const val ELECTRUM_CLIENT_NAME = "3.3.6"
        const val ELECTRUM_PROTOCOL_VERSION = "1.4"
        val version = ServerVersion()
        internal fun computeScriptHash(publicKeyScript: ByteVector): ByteVector32 = Crypto.sha256(publicKeyScript).toByteVector32().reversed()
    }
}