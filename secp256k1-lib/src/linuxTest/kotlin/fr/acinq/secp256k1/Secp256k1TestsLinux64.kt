package fr.acinq.secp256k1

import kotlinx.cinterop.*
import secp256k1.*
import kotlin.test.Test
import kotlin.test.assertEquals

class Secp256k1TestsLinux64 {
    fun encode(input: ByteArray, offset: Int, len: Int): String {
        val hexCode = arrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
        val r = StringBuilder(len * 2)
        for (i in 0 until len) {
            val b = input[offset + i]
            r.append(hexCode[(b.toInt() shr 4) and 0xF])
            r.append(hexCode[b.toInt() and 0xF])
        }
        return r.toString()
    }

    @Test
    fun `compute pubkey`() {
        val output = nativeHeap.allocArray<UByteVar>(65)
        val ctx = secp256k1_context_create((SECP256K1_FLAGS_TYPE_CONTEXT or SECP256K1_FLAGS_BIT_CONTEXT_SIGN).toUInt())
        val pub = nativeHeap.alloc<secp256k1_pubkey>()
        val priv = nativeHeap.allocArray<UByteVar>(32)
        for(i in 0 until 32) priv[i] = 0x01.toUByte()
        var result = secp256k1_ec_pubkey_create(ctx, pub.ptr, priv)
        assertEquals(1, result)
        val len = cValuesOf(65UL)
        result = secp256k1_ec_pubkey_serialize(ctx, output, len, pub.ptr, SECP256K1_EC_COMPRESSED)
        assertEquals(1, result)
        val bar = ByteArray(33)
        for(i in 0 until 33) bar[i] = output[i].toByte()
        assertEquals("031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f", encode(bar, 0, bar.size))
        secp256k1_context_destroy(ctx)
    }
}