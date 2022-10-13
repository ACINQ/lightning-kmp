@file:UseContextualSerialization(BlockHeader::class)

package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.crypto.ShaChain
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization

@Serializable
data class ErrorInformationLeak(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments
) : ChannelStateWithCommitments() {
    override fun processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this, listOf())
    }

    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments {
        return this.copy(commitments = input)
    }

    override fun handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this, listOf())
    }
}
