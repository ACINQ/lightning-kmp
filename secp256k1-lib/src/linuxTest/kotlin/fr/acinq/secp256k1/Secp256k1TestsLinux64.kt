package fr.acinq.secp256k1

import fr.acinq.sep256k1.Hex
import kotlinx.cinterop.*
import secp256k1.*
import kotlin.test.Test
import kotlin.test.assertEquals

class Secp256k1TestsLinux64 {
    @Test
    fun `compute pubkey (low-level API)`() {
        val output = nativeHeap.allocArray<UByteVar>(65)
        val ctx = secp256k1_context_create((SECP256K1_FLAGS_TYPE_CONTEXT or SECP256K1_FLAGS_BIT_CONTEXT_SIGN).toUInt())
        val pub = nativeHeap.alloc<secp256k1_pubkey>()
        val priv = nativeHeap.allocArray<UByteVar>(32)
        for (i in 0 until 32) priv[i] = 0x01.toUByte()
        var result = secp256k1_ec_pubkey_create(ctx, pub.ptr, priv)
        assertEquals(1, result)
        val len = cValuesOf(65UL)
        result = secp256k1_ec_pubkey_serialize(ctx, output, len, pub.ptr, SECP256K1_EC_COMPRESSED)
        assertEquals(1, result)
        val bar = ByteArray(33)
        for (i in 0 until 33) bar[i] = output[i].toByte()
        assertEquals("031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f", Hex.encode(bar))
        secp256k1_context_destroy(ctx)
    }

    @Test
    fun `compute pubkey`() {
        val priv = Hex.decode("0101010101010101010101010101010101010101010101010101010101010101")
        val pub = Secp256k1.computePublicKey(priv)
        assertEquals("031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f", Hex.encode(pub))
    }

    @Test
    fun `ecdh`() {
        val priv = Hex.decode("0101010101010101010101010101010101010101010101010101010101010101")
        val pub = Hex.decode("031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f")
        val secret = Secp256k1.ecdh(priv, pub)
        assertEquals("60bff7e2a4e757df5e69ed48632b993b9447ff480784964c6dc587ceef975a27", Hex.encode(secret))
    }
}