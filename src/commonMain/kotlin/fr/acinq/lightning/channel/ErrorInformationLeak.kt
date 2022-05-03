package fr.acinq.lightning.channel

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.lightning.blockchain.fee.OnChainFeerates

data class ErrorInformationLeak(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments
) : ChannelStateWithCommitments() {
    override fun processInternal(event: ChannelEvent): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this, listOf())
    }

    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments {
        return this.copy(commitments = input)
    }

    override fun handleLocalError(event: ChannelEvent, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this, listOf())
    }
}
