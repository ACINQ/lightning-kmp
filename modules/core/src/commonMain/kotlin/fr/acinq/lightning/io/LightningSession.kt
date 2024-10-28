package fr.acinq.lightning.io

import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.lightning.crypto.noise.CipherState
import fr.acinq.lightning.crypto.noise.ExtendedCipherState

class LightningSession(val enc: CipherState, val dec: CipherState, ck: ByteArray) {
    var encryptor: CipherState = ExtendedCipherState(enc, ck)
    var decryptor: CipherState = ExtendedCipherState(dec, ck)

    suspend fun receive(readBytes: suspend (Int) -> ByteArray): ByteArray {
        val cipherlen = readBytes(18)
        val (tmp, plainlen) = decryptor.decryptWithAd(ByteArray(0), cipherlen)
        decryptor = tmp
        // length is encoded as a signed int16 and returned as a Short, we need an unsigned int16
        val length = Pack.int16BE(plainlen).toInt() and 0xffff
        val cipherbytes = readBytes(length + 16)
        val (tmp1, plainbytes) = decryptor.decryptWithAd(ByteArray(0), cipherbytes)
        decryptor = tmp1
        return plainbytes
    }

    suspend fun send(data: ByteArray, write: suspend (ByteArray, flush: Boolean) -> Unit) {
        // the conversion here is not lossy if data.size() fits on 2 bytes, which it should
        val plainlen = Pack.writeInt16BE(data.size.toShort())
        val (tmp, cipherlen) = encryptor.encryptWithAd(ByteArray(0), plainlen)
        encryptor = tmp
        write(cipherlen, false)
        val (tmp1, cipherbytes) = encryptor.encryptWithAd(ByteArray(0), data)
        encryptor = tmp1
        write(cipherbytes, true)
    }
}
