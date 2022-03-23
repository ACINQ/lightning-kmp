package fr.acinq.lightning.message

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.wire.MessageOnion
import fr.acinq.lightning.wire.OnionMessage
import fr.acinq.lightning.wire.RecipientData

class Postman(val privateKey: PrivateKey) {
    private val subscribed: HashMap<ByteVector32, (MessageOnion, RecipientData) -> Unit> = HashMap()

    fun processOnionMessage(msg: OnionMessage) {
        val blindedPrivateKey = RouteBlinding.derivePrivateKey(privateKey, msg.blindingKey)
        when (val decrypted = Sphinx.peel(blindedPrivateKey, ByteVector.empty, msg.onionRoutingPacket, msg.onionRoutingPacket.payload.size())) {
            is Either.Right -> try {
                val message = MessageOnion.read(decrypted.value.payload.toByteArray())
                val (decryptedPayload, nextBlinding) = RouteBlinding.decryptPayload(privateKey, msg.blindingKey, message.encryptedData)
                val relayInfo = RecipientData.read(decryptedPayload.toByteArray())
                if (decrypted.value.isLastPacket && subscribed.containsKey(relayInfo.pathId)) {
                    subscribed[relayInfo.pathId]!!(message, relayInfo)
                } else if (!decrypted.value.isLastPacket && relayInfo.nextnodeId == privateKey.publicKey()) {
                    // We may add ourselves to the route several times at the end to hide the real length of the route.
                    processOnionMessage(OnionMessage(relayInfo.nextBlindingOverride ?: nextBlinding, decrypted.value.nextPacket))
                }
                // Ignore messages to relay to other nodes
            } catch (_: Throwable) {
                // Ignore bad messages
            }
        }
    }

    fun subscribe(pathId: ByteVector32, receive: (MessageOnion, RecipientData) -> Unit) {
        subscribed[pathId] = receive
    }

    fun unsubscribe(pathId: ByteVector32) {
        subscribed.remove(pathId)
    }
}