package fr.acinq.eklair.router

import fr.acinq.bitcoin.PublicKey
import fr.acinq.eklair.CltvExpiryDelta
import fr.acinq.eklair.Eclair
import fr.acinq.eklair.MilliSatoshi
import fr.acinq.eklair.wire.ChannelUpdate

interface Hop {
    /** @return the id of the start node. */
    val nodeId: PublicKey

    /** @return the id of the end node. */
    val nextNodeId: PublicKey

    /**
     * @param amount amount to be forwarded.
     * @return total fee required by the current hop.
     */
    fun fee(amount: MilliSatoshi): MilliSatoshi

    /** @return cltv delta required by the current hop. */
    val cltvExpiryDelta: CltvExpiryDelta
}

/**
 * A directed hop between two connected nodes using a specific channel.
 *
 * @param nodeId     id of the start node.
 * @param nextNodeId id of the end node.
 * @param lastUpdate last update of the channel used for the hop.
 */
data class ChannelHop(override val nodeId: PublicKey, override val nextNodeId: PublicKey, val lastUpdate: ChannelUpdate) : Hop {
    override val cltvExpiryDelta: CltvExpiryDelta = lastUpdate.cltvExpiryDelta

    override fun fee(amount: MilliSatoshi): MilliSatoshi = Eclair.nodeFee(lastUpdate.feeBaseMsat, lastUpdate.feeProportionalMillionths, amount)
}

/**
 * A directed hop between two trampoline nodes.
 * These nodes need not be connected and we don't need to know a route between them.
 * The start node will compute the route to the end node itself when it receives our payment.
 *
 * @param nodeId          id of the start node.
 * @param nextNodeId      id of the end node.
 * @param cltvExpiryDelta cltv expiry delta.
 * @param fee             total fee for that hop.
 */
data class NodeHop(override val nodeId: PublicKey, override val nextNodeId: PublicKey, override val cltvExpiryDelta: CltvExpiryDelta, val fee: MilliSatoshi) : Hop {
    override fun fee(amount: MilliSatoshi): MilliSatoshi = fee
}

