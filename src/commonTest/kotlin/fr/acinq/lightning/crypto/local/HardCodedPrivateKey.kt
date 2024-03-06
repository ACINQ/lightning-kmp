package fr.acinq.lightning.crypto.local

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.crypto.PrivateKeyDescriptor


class HardCodedPrivateKey(private val key: PrivateKey) : LocalPrivateKeyDescriptor {
    constructor(key: String): this(PrivateKey.fromHex(key))
    constructor(key: ByteArray): this(PrivateKey(key))
    constructor(key: ByteVector32): this(PrivateKey(key))
    override fun instantiate(): PrivateKey {
        return key
    }
}
