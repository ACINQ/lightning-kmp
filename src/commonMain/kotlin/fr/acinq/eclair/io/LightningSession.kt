package fr.acinq.eclair.io

import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.eclair.crypto.noise.CipherState
import fr.acinq.eclair.crypto.noise.ExtendedCipherState
import fr.acinq.eclair.utils.eclairLogger
import fr.acinq.eclair.utils.getValue
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue
import kotlin.time.nanoseconds

@ExperimentalUnsignedTypes
@ExperimentalTime
class LightningSession(val enc: CipherState, val dec: CipherState, ck: ByteArray) {
    private var encryptor: CipherState = ExtendedCipherState(enc, ck)
    private var decryptor: CipherState = ExtendedCipherState(dec, ck)

    private val logger by eclairLogger()

    private var totalDecryptedBytes: ULong = 0UL
    private var totalDecryptedTime: Duration = 0.nanoseconds

    private var totalEncryptedBytes: ULong = 0UL
    private var totalEncryptedTime: Duration = 0.nanoseconds

    suspend fun receive(readBytes: suspend (Int) -> ByteArray): ByteArray {

        val cipherlen = readBytes(18)
        val (tuple0, elapsed0) = measureTimedValue {
            decryptor.decryptWithAd(ByteArray(0), cipherlen)
        }
        decryptor = tuple0.first
        val plainlen = tuple0.second
        // length is encoded as a signed int16 and returned as a Short, we need an unsigned int16
        val length = Pack.int16BE(plainlen).toInt() and 0xffff
        val cipherbytes = readBytes(length + 16)
        val (tuple1, elapsed1) = measureTimedValue {
            decryptor.decryptWithAd(ByteArray(0), cipherbytes)
        }
        decryptor = tuple1.first
        val plainbytes = tuple1.second

        totalDecryptedBytes += (18 + length + 16).toULong()
        totalDecryptedTime += (elapsed0 + elapsed1)
        val avg = totalDecryptedTime.inNanoseconds / totalDecryptedBytes.toDouble()
        logger.info { "decryption overhead: $avg nanoseconds per byte"}

        return plainbytes
    }

    suspend fun send(data: ByteArray, write: suspend (ByteArray, flush: Boolean) -> Unit) {
        // the conversion here is not lossy if data.size() fits on 2 bytes, which it should
        val plainlen = Pack.writeInt16BE(data.size.toShort())
        val (tuple0, elapsed0) = measureTimedValue {
            encryptor.encryptWithAd(ByteArray(0), plainlen)
        }
        encryptor = tuple0.first
        val cipherlen = tuple0.second

        write(cipherlen, false)
        val (tuple1, elapsed1) = measureTimedValue {
            encryptor.encryptWithAd(ByteArray(0), data)
        }
        encryptor = tuple1.first
        val cipherbytes = tuple1.second
        write(cipherbytes, true)

        totalEncryptedBytes += (plainlen.size + data.size).toULong()
        totalEncryptedTime += (elapsed0 + elapsed1)
        val avg = totalEncryptedTime.inNanoseconds / totalEncryptedBytes.toDouble()
        logger.info { "encryption overhead: $avg nanoseconds per byte"}
    }
}
