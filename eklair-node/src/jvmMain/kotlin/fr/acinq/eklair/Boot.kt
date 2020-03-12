package fr.acinq.eklair

import fr.acinq.eklair.crypto.Pack.uint16
import fr.acinq.eklair.crypto.Pack.write16
import fr.acinq.eklair.crypto.noise.*
import fr.acinq.secp256k1.Secp256k1
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

@ExperimentalStdlibApi
object Boot {
    @JvmStatic
    fun main(args: Array<String>) {
        val priv = ByteArray(32) { 0x01.toByte() }
        val pub = Secp256k1.computePublicKey(priv)
        val keyPair = Pair(pub, priv)
        val nodeId = Hex.decode("032ddfc80ee130a8f10823649fcc2fdeb88281acf89f568a41cbdde96530b9f9f7")

        val prefix: Byte = 0x00
        val prologue = "lightning".encodeToByteArray()


        fun makeWriter(localStatic: Pair<ByteArray, ByteArray>, remoteStatic: ByteArray) = HandshakeState.initializeWriter(
                handshakePatternXK, prologue,
                localStatic, Pair(ByteArray(0), ByteArray(0)), remoteStatic, ByteArray(0),
                Secp256k1DHFunctions, Chacha20Poly1305CipherFunctions, SHA256HashFunctions)

        fun makeReader(localStatic: Pair<ByteArray, ByteArray>) = HandshakeState.initializeReader(
                handshakePatternXK, prologue,
                localStatic, Pair(ByteArray(0), ByteArray(0)), ByteArray(0), ByteArray(0),
                Secp256k1DHFunctions, Chacha20Poly1305CipherFunctions, SHA256HashFunctions)

        class LightningSession(val r: ByteReadChannel, val w: ByteWriteChannel, val enc: CipherState, val dec: CipherState, val ck: ByteArray) {
            var encryptor: CipherState = ExtendedCipherState(enc, ck)
            var decryptor: CipherState = ExtendedCipherState(dec, ck)

            suspend fun receive() : ByteArray {
                val cipherlen = ByteArray(18)
                r.readFully(cipherlen, 0,18)
                val (tmp, plainlen) = decryptor.decryptWithAd(ByteArray(0), cipherlen)
                decryptor = tmp
                val length = uint16(plainlen, 0)
                val cipherbytes = ByteArray(length + 16)
                r.readFully(cipherbytes, 0, cipherbytes.size)
                val (tmp1, plainbytes) = decryptor.decryptWithAd(ByteArray(0), cipherbytes)
                decryptor = tmp1
                return plainbytes
            }

            suspend fun send(data: ByteArray): Unit {
                val plainlen = write16(data.size)
                val (tmp, cipherlen) = encryptor.encryptWithAd(ByteArray(0), plainlen)
                encryptor = tmp
                w.writeFully(cipherlen, 0, cipherlen.size)
                val (tmp1, cipherbytes) = encryptor.encryptWithAd(ByteArray(0), data)
                encryptor = tmp1
                w.writeFully(cipherbytes, 0, cipherbytes.size)
                w.flush()
            }
        }

        suspend fun handshake(ourKeys: Pair<ByteArray, ByteArray>, theirPubkey: ByteArray, r: ByteReadChannel, w: ByteWriteChannel) : Triple<CipherState, CipherState, ByteArray> {

            /**
             * See BOLT #8: during the handshake phase we are expecting 3 messages of 50, 50 and 66 bytes (including the prefix)
             *
             * @param reader handshake state reader
             * @return the size of the message the reader is expecting
             */
            fun expectedLength(reader: HandshakeStateReader): Int = when (reader.messages.size) {
                3, 2 -> 50
                1 -> 66
                else -> throw RuntimeException("invalid state")
            }

            val writer = makeWriter(ourKeys, theirPubkey)
            val (state1, message, _) = writer.write(ByteArray(0))
            w.writeByte(prefix)
            w.writeFully(message, 0, message.size)
            w.flush()

            val packet = r.readPacket(expectedLength(state1), 0)
            val payload = packet.readBytes(expectedLength(state1))
            assert(payload[0] == prefix)

            val (writer1, a, b) = state1.read(payload.drop(1).toByteArray())
            val (reader1, message1, foo) = writer1.write(ByteArray(0))
            val (enc, dec, ck) = foo!!
            w.writeByte(prefix)
            w.writeFully(message1, 0, message1.size)
            w.flush()
            assert(packet.remaining == 0L)
            packet.close()
            return Triple(enc, dec, ck)
        }


        val address = InetSocketAddress("localhost", 48000)

        runBlocking {
            val socket = aSocket(ActorSelectorManager(Dispatchers.IO)).tcp().connect(address)
            val w = socket.openWriteChannel(autoFlush = false)
            val r = socket.openReadChannel()
            val (enc, dec, ck) = handshake(keyPair, nodeId, r, w)
            val session = LightningSession(r, w, enc, dec, ck)
            val ping = Hex.decode("0012000a0004deadbeef")
            while(true) {
                session.send(ping)
                val received = session.receive()
                println(Hex.encode(received))
            }
        }
    }
}