package fr.acinq.secp256k1

import kotlinx.cinterop.*
import secp256k1.*
import kotlin.test.assertEquals

actual object Secp256k1 {

    private val ctx: CPointer<secp256k1_context>? by lazy {
        secp256k1_context_create((SECP256K1_FLAGS_TYPE_CONTEXT or SECP256K1_FLAGS_BIT_CONTEXT_SIGN or SECP256K1_FLAGS_BIT_CONTEXT_VERIFY).toUInt())
    }

    actual fun computePublicKey(priv: ByteArray): ByteArray {
        require(ctx != null)
        require(priv.size == 32)
        memScoped {
            val nativePub = nativeHeap.alloc<secp256k1_pubkey>()
            val nativePriv = toNat(priv)
            var result = secp256k1_ec_pubkey_create(ctx, nativePub.ptr, nativePriv)
            assertEquals(1, result)
            val len = cValuesOf(33UL)
            val ser = nativeHeap.allocArray<UByteVar>(33)
            result = secp256k1_ec_pubkey_serialize(ctx, ser, len, nativePub.ptr, SECP256K1_EC_COMPRESSED)
            assertEquals(1, result)
            val output = fromNat(ser, 33)
            return output
        }
    }

    private fun toNat(input: ByteArray): CArrayPointer<UByteVar> {
        val nat = nativeHeap.allocArray<UByteVar>(input.size)
        for (i in input.indices) nat[i] = input[i].toUByte()
        return nat
    }

    private fun fromNat(input: CArrayPointer<UByteVar>, len: Int): ByteArray {
        val output = ByteArray(len)
        for (i in 0 until len) output[i] = input[i].toByte()
        return output
    }

    actual fun ecdh(priv: ByteArray, pub: ByteArray): ByteArray {
        require(ctx != null)
        require(priv.size == 32)
        require(pub.size == 33)
        memScoped {
            val natPriv = toNat(priv)
            val natPubBytes = toNat(pub)
            val natPub = nativeHeap.alloc<secp256k1_pubkey>()
            val natOutput = nativeHeap.allocArray<UByteVar>(32)
            var result = secp256k1_ec_pubkey_parse(ctx, natPub.ptr, natPubBytes, 33UL)
            assert(result == 1)
            result = secp256k1_ecdh(ctx, natOutput, natPub.ptr, natPriv, null, null)
            assert(result == 1)
            val output = fromNat(natOutput, 32)
            return output
        }
    }
}
