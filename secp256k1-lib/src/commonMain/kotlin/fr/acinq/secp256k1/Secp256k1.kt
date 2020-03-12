package fr.acinq.secp256k1

expect object Secp256k1 {
    fun computePublicKey(priv: ByteArray): ByteArray

    fun ecdh(priv: ByteArray, pub: ByteArray): ByteArray
}