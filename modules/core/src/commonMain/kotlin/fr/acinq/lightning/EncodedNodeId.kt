package fr.acinq.lightning

import fr.acinq.bitcoin.PublicKey

sealed class EncodedNodeId {
    sealed class WithPublicKey : EncodedNodeId() {
        abstract val publicKey: PublicKey

        /** Standard case where a node is identified by its public key. */
        data class Plain(override val publicKey: PublicKey) : WithPublicKey() {
            override fun toString(): String = publicKey.toString()
        }

        /**
         * Wallet nodes are not part of the public graph, and may not have channels yet.
         * Wallet providers are usually able to contact such nodes using push notifications or similar mechanisms.
         */
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
