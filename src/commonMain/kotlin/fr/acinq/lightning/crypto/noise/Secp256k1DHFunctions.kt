package fr.acinq.lightning.crypto.noise

import fr.acinq.secp256k1.Secp256k1


object Secp256k1DHFunctions : DHFunctions {
    override fun name() = "secp256k1"

    override fun generateKeyPair(priv: ByteArray): Pair<ByteArray, ByteArray> {
        require(priv.size == 32) { "private key size must be 32 bytes" }
        val pub = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(priv))
        return Pair(pub, priv)
    }

    /**
     * this is what secp256k1's secp256k1_ecdh() returns
     *
     * @param keyPair
     * @param publicKey
     * @return sha256(publicKey * keyPair.priv in compressed format)
     */
    override fun dh(keyPair: Pair<ByteArray, ByteArray>, publicKey: ByteArray): ByteArray {
        val secret = Secp256k1.ecdh(keyPair.second, publicKey)
        return secret
    }

    override fun dhLen(): Int = 32

    override fun pubKeyLen(): Int = 33 // we use compressed public keys
}