package fr.acinq.secp256k1

import kotlinx.cinterop.*
import kotlinx.cinterop.nativeHeap.alloc
import platform.posix.size_t
import secp256k1.*

actual class Secp256k1 {

    private var ctx:  CPointer<secp256k1_context>? = null

    actual fun initContext() {
        val foo = secp256k1_context_create(1U)
    }

    actual fun freeContext() {
        ctx = null
    }

    fun computePublicKey(input: ByteArray, output: ByteArray): Int {
        return 0
//        require(input.size == 32)
//        require(output.size == 65)
//        output.usePinned { pinnedOut ->
//            val pub = cValue<secp256k1_pubkey> {
//                pinnedOut.addressOf(0).rawValue
//              }
//            input.usePinned { pinnedIn ->
//                return secp256k1_ec_pubkey_create(ctx, pub, pinnedIn.addressOf(0) as CValuesRef<UByteVar>)
//            }
//        }
    }
}

fun main() {
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

    val input = ByteArray(32){ 0x01.toByte()}
    val output = ByteArray(65)
    val ctx = secp256k1_context_create((SECP256K1_FLAGS_TYPE_CONTEXT or SECP256K1_FLAGS_BIT_CONTEXT_SIGN).toUInt())
    val result = output.usePinned { pinnedOut ->
        val pub = cValue<secp256k1_pubkey>()
        input.usePinned { pinnedIn ->
            var result = secp256k1_ec_pubkey_create(ctx, pub, pinnedIn.addressOf(0) as CValuesRef<UByteVar>)
            println(result)
            var len: size_t = 65UL
            val foo = cValuesOf(len)
            result = secp256k1_ec_pubkey_serialize(ctx, pinnedOut.addressOf(0) as CValuesRef<UByteVar>, foo, pub, SECP256K1_EC_UNCOMPRESSED)
            println(result)
            println(len)
            println(encode(input, 0, input.size))
            println(encode(output, 0, output.size))
        }
    }
    println(ctx)

}