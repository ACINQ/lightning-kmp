package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey

class HardCodedPrivateKey(val keyAsHexString: String) : PrivateKeyDescriptor {
    override fun instantiate(): PrivateKey {
        return PrivateKey.fromHex(keyAsHexString)
    }

    override fun publicKey(): PublicKey {
        return instantiate().publicKey()
    }
}
