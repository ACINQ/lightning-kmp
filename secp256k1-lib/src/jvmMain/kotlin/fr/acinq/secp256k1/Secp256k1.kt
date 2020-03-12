package fr.acinq.secp256k1

actual object Secp256k1 {
    actual fun computePublicKey(priv: ByteArray): ByteArray {
        require(priv.size == 32)
        val pub = org.bitcoin.NativeSecp256k1.computePubkey(priv)
        // secp256k1 returns pubkeys in uncompressed format, we need to compress them
        pub[0] = if (pub[64].rem(2) == 0) 0x02.toByte() else 0x03.toByte()
        return pub.copyOfRange(0, 33)
    }

    actual fun ecdh(priv: ByteArray, pub: ByteArray): ByteArray {
        require(priv.size == 32)
        require(pub.size == 33)
        // pub is compressed, we need to uncompress it first
        val pub1 = org.bitcoin.NativeSecp256k1.parsePubkey(pub)
        return org.bitcoin.NativeSecp256k1.createECDHSecret(priv, pub1)
    }
}