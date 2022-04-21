package fr.acinq.lightning.tests.io.peer

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.channel.ChannelState
import fr.acinq.lightning.io.BytesReceived
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.wire.LightningMessage
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.flow.*

suspend inline fun <reified LNMessage : LightningMessage> Flow<LightningMessage>.expect(): LightningMessage = first { it is LNMessage }

@OptIn(ObsoleteCoroutinesApi::class)
suspend inline fun Peer.forward(message: LightningMessage) = send((BytesReceived(LightningMessage.encode(message))))

@OptIn(ObsoleteCoroutinesApi::class)
suspend inline fun Peer.expectStatus(await: Connection) = connectionState.first {
    when (await) {
        is Connection.ESTABLISHED -> it is Connection.ESTABLISHED
        is Connection.ESTABLISHING -> it is Connection.ESTABLISHING
        is Connection.CLOSED -> it is Connection.CLOSED
    }
}

@OptIn(ObsoleteCoroutinesApi::class)
suspend inline fun <reified Status : ChannelState> Peer.expectState(
    id: ByteVector32? = null,
    noinline waitCondition: (suspend Status.() -> Boolean)? = null,
): Pair<ByteVector32, Status> =
    channelsFlow
        .mapNotNull { map ->
            map.entries.find {
                (id == null || it.key == id) &&
                        it.value is Status &&
                        waitCondition?.invoke(it.value as Status) ?: true
            }
        }
        .map { it.key to it.value as Status }
        .first()
