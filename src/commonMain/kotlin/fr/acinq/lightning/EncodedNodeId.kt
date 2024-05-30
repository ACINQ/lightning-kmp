package fr.acinq.lightning

import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.wire.LightningCodecs

sealed class EncodedNodeId {
    sealed class WithPublicKey : EncodedNodeId() {
        abstract val publicKey: PublicKey

        /** Standard case where a node is identified by its public key */
        data class Plain(override val publicKey: PublicKey) : WithPublicKey() {
            override fun toString(): String = publicKey.toString()
        }

        data class Wallet(override val publicKey: PublicKey) : WithPublicKey() {
            override fun toString(): String = publicKey.toString()
        }
    }

    /** For compactness, nodes may be identified by the shortChannelId of one of their public channels. */
    data class ShortChannelIdDir(val isNode1: Boolean, val scid: ShortChannelId) : EncodedNodeId() {
        override fun toString(): String = if (isNode1) "<-$scid" else "$scid->"
    }

    companion object {
        operator fun invoke(publicKey: PublicKey): WithPublicKey.Plain = WithPublicKey.Plain(publicKey)
    }
}
