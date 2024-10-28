package fr.acinq.lightning.crypto.noise

import fr.acinq.bitcoin.crypto.Pack

/**
 * extended cipher state which implements key rotation as per BOLT #8
 * message format is:
 * +-------------------------------
 * |2-byte encrypted message length|
 * +-------------------------------
 * |  16-byte MAC of the encrypted |
 * |        message length         |
 * +-------------------------------
 * |                               |
 * |                               |
 * |     encrypted lightning       |
 * |            message            |
 * |                               |
 * +-------------------------------
 * |     16-byte MAC of the        |
 * |      lightning message        |
 * +-------------------------------

 * @param cs cipher state
 * @param ck chaining key
 */
data class ExtendedCipherState(val cs: CipherState, val ck: ByteArray) :
    CipherState {
    override fun cipher(): CipherFunctions = cs.cipher()

    override fun hasKey(): Boolean = cs.hasKey()

    override fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): Pair<CipherState, ByteArray> {
        return when {
            cs is UninitializedCipherState -> Pair(this, plaintext)
            cs is InitializedCipherState && cs.n == 999L -> {
                val (_, ciphertext) = cs.encryptWithAd(ad, plaintext)
                val (ck1, k1) = SHA256HashFunctions.hkdf(ck, cs.k)
                Pair(this.copy(cs = cs.initializeKey(k1), ck = ck1), ciphertext)
            }
            cs is InitializedCipherState -> {
                val (cs1, ciphertext) = cs.encryptWithAd(ad, plaintext)
                Pair(this.copy(cs = cs1), ciphertext)
            }
            else -> throw IllegalArgumentException("invalid cipher state")
        }
    }

    override fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): Pair<CipherState, ByteArray> {
        return when {
            cs is UninitializedCipherState -> Pair(this, ciphertext)
            cs is InitializedCipherState && cs.n == 999L -> {
                val (_, plaintext) = cs.decryptWithAd(ad, ciphertext)
                val (ck1, k1) = SHA256HashFunctions.hkdf(ck, cs.k)
                Pair(this.copy(cs = cs.initializeKey(k1), ck = ck1), plaintext)
            }
            cs is InitializedCipherState -> {
                val (cs1, plaintext) = cs.decryptWithAd(ad, ciphertext)
                Pair(this.copy(cs = cs1), plaintext)
            }
            else -> throw IllegalArgumentException("invalid cipher state")
        }
    }

    fun encrypt(plaintext: ByteArray): Pair<ExtendedCipherState, ByteArray> {
        val plainlen = Pack.writeInt16BE(plaintext.size.toShort())
        val (tmp, cipherlen) = this.encryptWithAd(ByteArray(0), plainlen)
        val (tmp1, cipherbytes) = tmp.encryptWithAd(ByteArray(0), plaintext)
        return Pair(tmp1 as ExtendedCipherState, cipherlen + cipherbytes)
    }
}