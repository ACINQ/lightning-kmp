package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey


class HardCodedPrivateKey(private val key: PrivateKey) : PrivateKeyDescriptor {
    constructor(key: String): this(PrivateKey.fromHex(key))
    constructor(key: ByteArray): this(PrivateKey(key))
    constructor(key: ByteVector32): this(PrivateKey(key))
    override fun instantiate(): PrivateKey {
        return key
    }

    override fun publicKey(): PublicKey {
        return instantiate().publicKey()
    }
}
