package fr.acinq.eklair

import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

object Boot {
    @JvmStatic
    fun main(args: Array<String>) {

        println(Hex.encode(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())))

        val address = InetSocketAddress("localhost", 48000)

        runBlocking {
            val socket = aSocket(ActorSelectorManager(Dispatchers.Default)).tcp().connect(address)
            val w = socket.openWriteChannel(autoFlush = false)
            val r = socket.openReadChannel()
//            val (enc, dec, ck) = handshake(keyPair, nodeId, r, w)
//            val session = LightningSession(r, w, enc, dec, ck)
//            val ping = Hex.decode("0012000a0004deadbeef")
//            session.send(ping)
//            val received = session.receive()
//            println(Hex.decode(received))
//            session.send(ping)
//            // handshake is over now, we have our 2 cipher states (incoming and outgoing) and our cipher key
            delay(5000)
        }

    }
}