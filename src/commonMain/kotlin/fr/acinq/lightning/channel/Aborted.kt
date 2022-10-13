@file:UseContextualSerialization(BlockHeader::class)

package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization

/**
 * Channel has been aborted before it was funded (because we did not receive a FundingCreated or FundingSigned message for example)
 */
@Serializable
data class Aborted(override val staticParams: StaticParams, override val currentTip: Pair<Int, BlockHeader>, override val currentOnChainFeerates: OnChainFeerates) : ChannelState() {
    override fun processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this, listOf())
    }

    override fun handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this, listOf())
    }
}
