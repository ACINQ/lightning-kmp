package fr.acinq.eklair

import fr.acinq.bitcoin.crypto.Secp256k1
import fr.acinq.bitcoin.Hex
import fr.acinq.eklair.EklairClient.nodeId
import fr.acinq.eklair.io.LightningSession
import fr.acinq.eklair.io.SocketBuilder
import fr.acinq.eklair.io.SocketHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce

sealed class EklairActorMessage
object ConnectMsg : EklairActorMessage()
object HandshakeMsg : EklairActorMessage()
object InitMsg : EklairActorMessage()
object PingMsg : EklairActorMessage()
object HostMsg : EklairActorMessage()
object Disconnect : EklairActorMessage()
object EmptyMsg : EklairActorMessage()

class HostResponseMessage(val response: String) : EklairActorMessage()

internal class GetHostMessage(val response: CompletableDeferred<String>) : EklairActorMessage()

object EklairObjects {
    val connect = ConnectMsg
    val handshake = HandshakeMsg
    val init = InitMsg
    val ping = PingMsg
    val disconnect = Disconnect
    val hostMsg = HostMsg
    val none = EmptyMsg
}

object EklairClient {
    val priv = ByteArray(32) { 0x01.toByte() }
    val pub = Secp256k1.computePublicKey(priv)
    val keyPair = Pair(pub, priv)
    val nodeId = Hex.decode("02413957815d05abb7fc6d885622d5cdc5b7714db1478cb05813a8474179b83c5c")

}

class EklairActor() {
    var socketHandler: SocketHandler? = null
    var handshake: EklairHandshake? = null
    var session: LightningSession? = null
}

@InternalCoroutinesApi
fun CoroutineScope.eklairActor() = actor<EklairActorMessage> {
    var state = EklairActor()
    for (msg in channel) {
        println("⚡ Got $msg in channel...")
        when (msg) {
            is ConnectMsg -> {
                state.socketHandler = SocketBuilder.buildSocketHandler("51.77.223.203", 19735)
            }
            is HandshakeMsg -> {
                state.socketHandler?.let {
                    state.handshake = EklairAPI.handshake(EklairClient.keyPair, nodeId, it)
                }
            }
            is InitMsg -> {
                state.socketHandler?.let { handler ->
                    state.handshake?.let { handshake ->
                        val session = LightningSession(handler, handshake.first, handshake.second, handshake.third)
                        val init = Hex.decode("001000000002a8a0")
                        session.send(init)
                        val initResult = session.receive()
                        println("⚡ ${Hex.encode(initResult)}")
                        state.session = session
                    }
                }
            }
            is PingMsg -> {
                state.session?.let { session ->
                    val ping = Hex.decode("0012000a0004deadbeef")
                    session.send(ping)
                    delay(5000)
                    val received = session.receive()
                    println("⚡\uD83C\uDFD3 ${Hex.encode(received)}")
                }
            }
            is GetHostMessage -> {
                state.socketHandler?.apply {
                    msg.response.complete("${Hex.encode(nodeId)}@${getHost()}")
                }
            }
        }
    }
}

sealed class EklairMessage
class Connect(val handler: SocketHandler) : EklairMessage()
class Handshake(val handler: SocketHandler, val content: EklairHandshake) : EklairMessage()
class Init(val session: LightningSession) : EklairMessage()
class Ping(val data: ByteArray) : EklairMessage()


@ExperimentalCoroutinesApi
fun CoroutineScope.doConnect() = produce {
    val handler = SocketBuilder.buildSocketHandler("51.77.223.203", 19735)
    send(Connect(handler))
}

@ExperimentalCoroutinesApi
fun CoroutineScope.doHandshake(connection: ReceiveChannel<Connect>) = produce {
    val receive = connection.receive()
    val handshake = EklairAPI.handshake(EklairClient.keyPair, nodeId, receive.handler)
    send(Handshake(receive.handler, handshake))
}


@ExperimentalCoroutinesApi
fun CoroutineScope.doCreateSession(handshake: ReceiveChannel<Handshake>) = produce {
    val receive = handshake.receive()
    val session = LightningSession(receive.handler, receive.content.first, receive.content.second, receive.content.third)
    val init = Hex.decode("001000000002a8a0")
    session.send(init)
    val initResult = session.receive()
    println("⚡ ${Hex.encode(initResult)}")
    send(Init(session))
}


@ExperimentalCoroutinesApi
fun CoroutineScope.doPing(session: LightningSession) = produce {
    val ping = Hex.decode("0012000a0004deadbeef")
    session.send(ping)
    val received = session.receive()
    println("⚡\uD83C\uDFD3 ${Hex.encode(received)}")
    send(Ping(received))
}



