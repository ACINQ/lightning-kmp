package fr.acinq.secp256k1

expect class Secp256k1 {
    fun initContext()
    fun freeContext()
    //fun computePublicKey(input: ByteArray, output:ByteArray): Int
}