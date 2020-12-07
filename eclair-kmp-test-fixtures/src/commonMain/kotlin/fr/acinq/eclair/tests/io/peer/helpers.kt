package fr.acinq.eclair.tests.io.peer

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.ChannelState
import fr.acinq.eclair.io.BytesReceived
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.utils.Connection
import fr.acinq.eclair.wire.LightningMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow

suspend inline fun <reified LNMessage> Peer.expectMessage(): LNMessage = output.receiveAsFlow().first {
    val msg = LightningMessage.decode(it) ?: error("cannot decode lightning message $it")
    msg is LNMessage
}.let {  LightningMessage.decode(it) as LNMessage }

suspend inline fun Peer.forwardMessage(message: LightningMessage) = send((BytesReceived(LightningMessage.encode(message))))

suspend inline fun Peer.expectStatus(await: Connection) = connectionState.first { it == await }

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
