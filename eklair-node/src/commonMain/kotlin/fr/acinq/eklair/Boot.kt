package fr.acinq.eklair

import fr.acinq.eklair.EklairAPI.handshake
import fr.acinq.eklair.SocketBuilder.buildSocketHandler
import fr.acinq.eklair.SocketBuilder.runBlockingCoroutine
import fr.acinq.eklair.crypto.Pack.uint16
import fr.acinq.eklair.crypto.Pack.write16
import fr.acinq.eklair.crypto.noise.*
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.*


@ExperimentalStdlibApi
class EklairApp{
    fun run(){
        Boot.main(emptyArray())
    }
}

interface SocketHandler{
    fun getHost(): String
    suspend fun readUpTo(length: Int): ByteArray
    suspend fun readFully(dst: ByteArray, offset: Int, length: Int)
    suspend fun writeByte(b: Byte)
    suspend fun writeFully(src: ByteArray, offset: Int, length: Int)
    fun flush()
}



@ExperimentalStdlibApi
object Boot {
    fun main(args: Array<String>) {
        val priv = ByteArray(32) { 0x01.toByte() }
        val pub = Secp256k1.computePublicKey(priv)
        val keyPair = Pair(pub, priv)
        val nodeId = Hex.decode("02413957815d05abb7fc6d885622d5cdc5b7714db1478cb05813a8474179b83c5c")

        runBlockingCoroutine {
            val socketHandler = buildSocketHandler()
            val (enc, dec, ck) = handshake(keyPair, nodeId, socketHandler)
            val session = LightningSession(socketHandler, enc, dec, ck)
            val ping = Hex.decode("0012000a0004deadbeef")
            val init = Hex.decode("001000000002a8a0")
            session.send(init)
            while(true) {
                val received = session.receive()
                println(Hex.encode(received))
                delay(2000)
                session.send(ping)
            }
        }
    }

}

class LightningSession(val socketHandler: SocketHandler, val enc: CipherState, val dec: CipherState, val ck: ByteArray) {
    var encryptor: CipherState = ExtendedCipherState(enc, ck)
    var decryptor: CipherState = ExtendedCipherState(dec, ck)

    suspend fun receive() : ByteArray {
        val cipherlen = ByteArray(18)
        socketHandler.readFully(cipherlen, 0,18)
        val (tmp, plainlen) = decryptor.decryptWithAd(ByteArray(0), cipherlen)
        decryptor = tmp
        val length = uint16(plainlen, 0)
        val cipherbytes = ByteArray(length + 16)
        socketHandler.readFully(cipherbytes, 0, cipherbytes.size)
        val (tmp1, plainbytes) = decryptor.decryptWithAd(ByteArray(0), cipherbytes)
        decryptor = tmp1
        return plainbytes
    }

    suspend fun send(data: ByteArray): Unit {
        val plainlen = write16(data.size)
        val (tmp, cipherlen) = encryptor.encryptWithAd(ByteArray(0), plainlen)
        encryptor = tmp
        socketHandler.writeFully(cipherlen, 0, cipherlen.size)
        val (tmp1, cipherbytes) = encryptor.encryptWithAd(ByteArray(0), data)
        encryptor = tmp1
        socketHandler.writeFully(cipherbytes, 0, cipherbytes.size)
        socketHandler.flush()
    }
}


typealias EklairHandshake = Triple<CipherState, CipherState, ByteArray>

object EklairAPI{
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

    suspend fun handshake(ourKeys: Pair<ByteArray, ByteArray>, theirPubkey: ByteArray, socketHandler: SocketHandler) : EklairHandshake {

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
        socketHandler.writeByte(prefix)
        socketHandler.writeFully(message, 0, message.size)
        socketHandler.flush()

        val payload = socketHandler.readUpTo(expectedLength(state1))

        val (writer1, a, b) = state1.read(payload.drop(1).toByteArray())
        val (reader1, message1, foo) = writer1.write(ByteArray(0))
        val (enc, dec, ck) = foo!!
        socketHandler.writeByte(prefix)
        socketHandler.writeFully(message1, 0, message1.size)
        socketHandler.flush()
        return Triple(enc, dec, ck)
    }

}
expect object SocketBuilder {
    suspend fun buildSocketHandler(): SocketHandler

    fun runBlockingCoroutine(closure: suspend (CoroutineScope) -> Unit)

}
