package fr.acinq.eklair.io

import fr.acinq.eklair.crypto.Pack
import fr.acinq.eklair.crypto.noise.CipherState
import fr.acinq.eklair.crypto.noise.ExtendedCipherState

class LightningSession(val socketHandler: SocketHandler, val enc: CipherState, val dec: CipherState, val ck: ByteArray) {
    var encryptor: CipherState = ExtendedCipherState(enc, ck)
    var decryptor: CipherState = ExtendedCipherState(dec, ck)

    suspend fun receive(): ByteArray {
        val cipherlen = ByteArray(18)
        socketHandler.readFully(cipherlen, 0, 18)
        val (tmp, plainlen) = decryptor.decryptWithAd(ByteArray(0), cipherlen)
        decryptor = tmp
        val length = Pack.uint16(plainlen, 0)
        val cipherbytes = ByteArray(length + 16)
        socketHandler.readFully(cipherbytes, 0, cipherbytes.size)
        val (tmp1, plainbytes) = decryptor.decryptWithAd(ByteArray(0), cipherbytes)
        decryptor = tmp1
        return plainbytes
    }

    suspend fun send(data: ByteArray): Unit {
        val plainlen = Pack.write16(data.size)
        val (tmp, cipherlen) = encryptor.encryptWithAd(ByteArray(0), plainlen)
        encryptor = tmp
        socketHandler.writeFully(cipherlen, 0, cipherlen.size)
        val (tmp1, cipherbytes) = encryptor.encryptWithAd(ByteArray(0), data)
        encryptor = tmp1
        socketHandler.writeFully(cipherbytes, 0, cipherbytes.size)
        socketHandler.flush()
    }
}