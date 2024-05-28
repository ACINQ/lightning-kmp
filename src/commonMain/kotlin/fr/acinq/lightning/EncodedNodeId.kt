package fr.acinq.lightning

import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.wire.LightningCodecs

sealed class EncodedNodeId {
    /** Nodes are usually identified by their public key. */
    data class Plain(val publicKey: PublicKey) : EncodedNodeId() {
        override fun toString(): String = publicKey.toString()
    }

    /** For compactness, nodes may be identified by the shortChannelId of one of their public channels. */
    data class ShortChannelIdDir(val isNode1: Boolean, val scid: ShortChannelId) : EncodedNodeId() {
        override fun toString(): String = if (isNode1) "<-$scid" else "$scid->"
    }

    data class PhoenixId(val publicKey: PublicKey) : EncodedNodeId() {
        override fun toString(): String = publicKey.toString()
    }

    companion object {
        operator fun invoke(publicKey: PublicKey): EncodedNodeId = Plain(publicKey)
    }
}
