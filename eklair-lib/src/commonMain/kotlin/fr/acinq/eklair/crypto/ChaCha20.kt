package fr.acinq.eklair.crypto

import fr.acinq.eklair.crypto.Pack.intToLittleEndian
import fr.acinq.eklair.crypto.Pack.littleEndianToInt
import kotlin.experimental.and
import kotlin.experimental.or
import kotlin.experimental.xor

/*
 * Quick-n-dirty standalone implementation of ChaCha 256-bit
 * <p/>
 * Created by Clarence Ho on 20150729
 * <p/>
 * References:
 * ~ http://cr.yp.to/chacha/chacha-20080128.pdf
 * ~ https://tools.ietf.org/html/draft-irtf-cfrg-chacha20-poly1305-01
 * ~ https://github.com/quartzjer/chacha20
 * ~ https://github.com/jotcmd/chacha20
 */
class ChaCha20(key: ByteArray, nonce: ByteArray, counter: Int) {
    private val matrix = IntArray(16)

    init {
        if (key.size != KEY_SIZE) {
            throw IllegalArgumentException("invalid key size")
        }
        matrix[0] = 0x61707865
        matrix[1] = 0x3320646e
        matrix[2] = 0x79622d32
        matrix[3] = 0x6b206574
        matrix[4] = littleEndianToInt(key, 0)
        matrix[5] = littleEndianToInt(key, 4)
        matrix[6] = littleEndianToInt(key, 8)
        matrix[7] = littleEndianToInt(key, 12)
        matrix[8] = littleEndianToInt(key, 16)
        matrix[9] = littleEndianToInt(key, 20)
        matrix[10] = littleEndianToInt(key, 24)
        matrix[11] = littleEndianToInt(key, 28)
        when (nonce.size) {
            NONCE_SIZE_REF -> {        // reference implementation
                matrix[12] = 0
                matrix[13] = 0
                matrix[14] = littleEndianToInt(nonce, 0)
                matrix[15] = littleEndianToInt(nonce, 4)
            }
            NONCE_SIZE_IETF -> {
                matrix[12] = counter
                matrix[13] = littleEndianToInt(nonce, 0)
                matrix[14] = littleEndianToInt(nonce, 4)
                matrix[15] = littleEndianToInt(nonce, 8)
            }
            else -> {
                throw IllegalArgumentException("invalid nonce")
            }
        }
    }

    fun encrypt(dst: ByteArray, src: ByteArray, len: Int) {
        var len = len
        val x = IntArray(16)
        val output = ByteArray(64)
        var i: Int
        var dpos = 0
        var spos = 0
        while (len > 0) {
            i = 16
            while (i-- > 0) {
                x[i] = matrix[i]
            }
            i = 20
            while (i > 0) {
                quarterRound(x, 0, 4, 8, 12)
                quarterRound(x, 1, 5, 9, 13)
                quarterRound(x, 2, 6, 10, 14)
                quarterRound(x, 3, 7, 11, 15)
                quarterRound(x, 0, 5, 10, 15)
                quarterRound(x, 1, 6, 11, 12)
                quarterRound(x, 2, 7, 8, 13)
                quarterRound(x, 3, 4, 9, 14)
                i -= 2
            }
            i = 16
            while (i-- > 0) {
                x[i] += matrix[i]
            }
            i = 16
            while (i-- > 0) {
                intToLittleEndian(x[i], output, 4 * i)
            }

            // TODO: (1) check block count is 32-bit vs 64-bit; (2) java int is signed!
            matrix[12] += 1
            if (matrix[12] <= 0) {
                matrix[13] += 1
            }
            if (len <= 64) {
                i = len
                while (i-- > 0) {
                    dst[i + dpos] = (src[i + spos] xor output[i]) as Byte
                }
                return
            }
            i = 64
            while (i-- > 0) {
                dst[i + dpos] = (src[i + spos] xor output[i]) as Byte
            }
            len -= 64
            spos += 64
            dpos += 64
        }
    }

    fun decrypt(dst: ByteArray, src: ByteArray, len: Int) {
        encrypt(dst, src, len)
    }

    companion object {
        /*
     * Key size in byte
     */
        const val KEY_SIZE = 32

        /*
     * Nonce size in byte (reference implementation)
     */
        const val NONCE_SIZE_REF = 8

        /*
     * Nonce size in byte (IETF draft)
     */
        const val NONCE_SIZE_IETF = 12

        private fun ROTATE(v: Int, c: Int): Int {
            return v shl c or (v ushr 32 - c)
        }

        private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]
            x[d] = ROTATE(x[d] xor x[a], 16)
            x[c] += x[d]
            x[b] = ROTATE(x[b] xor x[c], 12)
            x[a] += x[b]
            x[d] = ROTATE(x[d] xor x[a], 8)
            x[c] += x[d]
            x[b] = ROTATE(x[b] xor x[c], 7)
        }

        fun encrypt(plaintext: ByteArray, key: ByteArray, nonce: ByteArray, counter: Int = 0): ByteArray {
            val engine = ChaCha20(key, nonce, counter)
            val ciphertext = ByteArray(plaintext.size)
            engine.encrypt(ciphertext, plaintext, plaintext.size)
            return ciphertext
        }

        fun decrypt(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray, counter: Int = 0): ByteArray = encrypt(ciphertext, key, nonce, counter)
    }
}